/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.upb.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Delayed;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.openhab.binding.upb.internal.UPBReader.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used to write data to the UPB modem.
 *
 * @author Chris Van Orman, Dustin Gerold
 * @since 1.9.0
 */
public class UPBWriter implements Listener
{
    /**
     * Time in milliseconds to wait for an ACK from the modem after writing a
     * message.
     */
    private static long ACK_TIMEOUT = 1000;

    private final Logger logger = LoggerFactory.getLogger(UPBWriter.class);

    /**
     * Queue for writing data to the UPB modem.
     */
    private BlockingQueue<Message> dqSendMessageQueue;
    private BlockingQueue<Message> dqReceiveMessageQueue;
    /**
     * The UPB modem's OutputStream.
     */
    private OutputStream outputStream;

    /**
     * UPBReader that is monitoring the modem's InputStream.
     */
    private UPBReader upbReader;

    private boolean boolContinueProcessing;
    private boolean waitingOnAck;
    private boolean ackReceived;

    private ExecutorService executor;

    /**
     * Instantiates a new {@link UPBWriter} using the given modem
     * {@link OutputStream}.
     *
     * @param outputStream
     *            the {@link OutputStream} from the UPB modem.
     * @param upbReader
     *            the {@link UPBReader} that is monitoring the same UPB modem.
     */
    public UPBWriter(OutputStream outputStream, UPBReader upbReader) 
    {
      try
      {
        this.outputStream = outputStream;
        this.upbReader = upbReader;
        upbReader.addListener(this);
        dqSendMessageQueue = new DelayQueue<Message>();
        dqReceiveMessageQueue = new DelayQueue<Message>();

        executor = Executors.newSingleThreadExecutor();

        executor.submit(new SendMessages(outputStream, dqSendMessageQueue, dqReceiveMessageQueue));
      }
      catch (Exception ex)
      {
        logger.debug("UPBWriter() error : " + ex.getMessage());
      }
    }

    /**
     * Queues a message to be written to the modem.
     *
     * @param message
     *            the message to be written.
     */
    public void queueMessage(MessageBuilder message) 
    {
      try
      {
        String data = message.build();
        logger.debug("Queueing message {}", data);

        Message mUPBMessage = new Message(data.getBytes(), message.getPriority());
     
        dqSendMessageQueue.add(mUPBMessage);
      }
      catch (Exception ex)
      {
        logger.debug("queueMessage() error : " + ex.getMessage());
      }
    }

    /**
     * Cancels all queued messages and releases resources. This instance cannot
     * be used again and a new {@link UPBWriter} must be instantiated after
     * calling this method.
     */
    public void shutdown() 
    {
      try
      {
        Message mUPBMessage = new Message(true);
        dqSendMessageQueue.add(mUPBMessage);

        upbReader.removeListener(this);
         
        executor.shutdown();

        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) 
        {
          executor.shutdownNow();
        }
      }
      catch (Exception e) 
      {
        logger.error("UPBWriter shutdown error : " + e.getMessage());
      }
    }

/*
      private synchronized void ackReceived(boolean ack) 
      {
        try
        {
          waitingOnAck = false;
          ackReceived = ack;
          notify();
        }
        catch (Exception e) 
        {
          logger.error("ackReceived() error : " + e.getMessage());
        }
      }

      private synchronized boolean waitForAck(byte[] data) 
      {
        try 
        {
          wait(ACK_TIMEOUT);
        } 
        catch (InterruptedException e) 
        {
          Thread.currentThread().interrupt();
          logger.error("Message waitForAck error : " + e.getMessage());
        }

        if (!waitingOnAck) 
        {
          if (ackReceived)
          {
            logger.debug("Message {} ack received.", new String(data));
          } 
          else 
          {
            logger.debug("Message {} not ack'd.", new String(data));
          }
        } 
        else 
        {
          logger.debug("Message {} ack timed out, will retry.", new String(data));
        }

        return ackReceived;
      }
*/

      /**
       * {@inheritDoc}
       */
      @Override
      public void messageReceived(UPBMessage message) 
      {
        byte[] data;
        Message mUPBMessage;

        try
        {
          data = new byte[1];
          data[0] = 0;

          switch (message.getType()) 
          {
            case BUSY:

            case NAK:
              //ackReceived(false);
              mUPBMessage = new Message(data, UPBMessage.Priority.NORMAL);
              dqReceiveMessageQueue.add(mUPBMessage);
              break;

            case ACK:
              //ackReceived(true);
              mUPBMessage = new Message(data, UPBMessage.Priority.NORMAL);
              mUPBMessage.setAcknowledged(true);
              dqReceiveMessageQueue.add(mUPBMessage);
              break;

            default:
          }
        }
        catch (Exception e) 
        {
          logger.error("Message messageReceived error : " + e.getMessage());
        }
      }

    /**
     * {@link Runnable} implementation used to write data to the UPB modem.
     *
     * @author Chris Van Orman
     *
     */
    private class Message implements Delayed
    {
      private byte[] data;
      private UPBMessage.Priority priority;
      private int intRetryCount;
      private long startTime;
      private final long lngFiveMinutes = 300000, lngTenMinutes = 600000, lngFifteenMinutes = 900000;
      private boolean boolKillMessage, boolAcknowledged;

      private Message(boolean boolContinue)
      {
        boolKillMessage = boolContinue;
      }

      private Message(byte[] data, UPBMessage.Priority priority) 
      {
        this.data = data;
        this.priority = priority;
        intRetryCount = 0;       
        long lngPriorityDelay = Long.valueOf(priority.getDelay());
        startTime = System.currentTimeMillis();
        boolKillMessage = false;
        boolAcknowledged = false;

        if (priority == UPBMessage.Priority.LOW)
        {
          startTime += lngPriorityDelay;
        }
      }

      @Override
      public long getDelay(TimeUnit unit)
      {
        long diff = startTime - System.currentTimeMillis();
        return unit.convert(diff, TimeUnit.MILLISECONDS);
      }

      @Override
      public int compareTo(Delayed obj)
      {
        if (this.startTime < ((Message)obj).startTime) 
        { 
          return -1; 
        }
 
        if (this.startTime > ((Message)obj).startTime) 
        { 
          return 1; 
        } 

        return 0;
      }
 
      public UPBMessage.Priority getPriority()
      {
        return priority;
      } 

      public boolean getAcknowledged()
      {
        return boolAcknowledged;
      }

      public void setAcknowledged(boolean boolValue)
      {
        boolAcknowledged = boolValue;
      }

      public int getRetryCount()
      {
        return intRetryCount;
      }

      public void setKillMessage(boolean boolContinue)
      {
        boolKillMessage = boolContinue;
      }

      public boolean getKillMessage()
      {
        return boolKillMessage;
      }

      public void increaseRetryCount()
      {
        intRetryCount += 1;

        startTime = System.currentTimeMillis();

        switch (intRetryCount) 
        {
          case 1:
            startTime += lngFiveMinutes;
            logger.debug("Waiting 5 minutes before retrying UPB message");
            break;

          case 2:
            startTime += lngTenMinutes;
            logger.debug("Waiting 10 minutes before retrying UPB message");
            break;

          case 3:
            startTime += lngFifteenMinutes;
            logger.debug("Waiting 15 minutes before retrying UPB message");
            break;

          case 4:
            startTime += lngFifteenMinutes;
            logger.debug("Waiting 15 minutes before retrying UPB message");
            break;

          case 5:
            startTime += lngFifteenMinutes;
            logger.debug("Waiting 15 minutes before retrying UPB message");
            break;

          default:
        }
      }

      public byte[] getData()
      {
        return this.data;
      }
    }

    class SendMessages implements Runnable 
    { 
      private BlockingQueue<Message> dqSendMessageQueue;
      private BlockingQueue<Message> dqReceiveMessageQueue;
      private boolean boolContinueProcessing, ackReceived;
      private OutputStream outputStream;
      private byte[] data;

      public SendMessages(OutputStream osOutputStream, BlockingQueue<Message> bqSendQueue, BlockingQueue<Message> bqReceiveQueue)
      {
        outputStream = osOutputStream;
        dqSendMessageQueue = bqSendQueue;
        dqReceiveMessageQueue = bqReceiveQueue;
        boolContinueProcessing = true;
        waitingOnAck = true;
        ackReceived = false;
      }
       
      public void run() 
      {
        Message mUPBMessage;
        Message mReceivedMessage;
 
        try
        {
          while(boolContinueProcessing)
          {
            try
            { 
              mReceivedMessage = null;
              ackReceived = false;
              mUPBMessage = dqSendMessageQueue.poll(5L, TimeUnit.MINUTES);

              if (mUPBMessage != null)
              {
                if (mUPBMessage.getKillMessage() == false)
                {
                  ackReceived = false;
                  data = mUPBMessage.getData();
       
                  logger.debug("Sending message {}", new String(data));
 
                  outputStream.write(0x14);
                  outputStream.write(data);
                  outputStream.write(0x0d);

                  mReceivedMessage = dqReceiveMessageQueue.poll(1000L, TimeUnit.MILLISECONDS);

                  if (mReceivedMessage != null)
                  {
                    if (mReceivedMessage.getAcknowledged() == true)
                    {
                      ackReceived = true;
                      logger.debug("Received acknowledgement of command {}", new String(data));
                    }
                    else
                    {
                      logger.debug("The device at {} did not acknowledge the command", new String(data));
                    }
                  }
                  else
                  {
                    logger.debug("The sent message of {} did not receive an acknowledgement!", new String(data));
                  }

                  if (!ackReceived)
                  {
                    if ((mUPBMessage.getPriority() == UPBMessage.Priority.NORMAL && mUPBMessage.getRetryCount() < 5) || (mUPBMessage.getPriority() == UPBMessage.Priority.LOW && mUPBMessage.getRetryCount() < 2))
                    {
                      mUPBMessage.increaseRetryCount();
 
                      dqSendMessageQueue.add(mUPBMessage);
                    }
                    else
                    {
                      logger.debug("Did not receive acknowledgement from UPB device after all retry attempts, no more attempts will be made!");
                    }
                  }
                }
                else
                {
                  boolContinueProcessing = false;
                }
              }
            }
            catch (InterruptedException ie)
            {
              boolContinueProcessing = false;
            }
            catch (Exception e)
            {
              logger.debug("sendMessages() error : " + e.getMessage());
            }
          }

          logger.debug("Shutdown in progress, evacuating queue");

          boolContinueProcessing = true;

          while(boolContinueProcessing)
          {
            try
            {
              if (dqSendMessageQueue.peek() != null)
              {
                mUPBMessage = dqSendMessageQueue.poll();

                if (mUPBMessage != null)
                {
                  data = mUPBMessage.getData();

                  logger.debug("Sending message {}", new String(data));

                  outputStream.write(0x14);
                  outputStream.write(data);
                  outputStream.write(0x0d);
                }
                else
                {
                  boolContinueProcessing = false;
                }
              }
              else
              {
                boolContinueProcessing = false;
              }
            }
            catch (Exception e)
            {
              logger.debug("sendMessages() queue flush error : " + e.getMessage());
            }
          }

          try
          {
            if (outputStream != null)
            {
              outputStream.close();
            }          
          }
          catch(Exception ex)
          {
            logger.debug("Error during UPBWriter shudown : " + ex.getMessage());
          }

          logger.debug("UPBWriter was shutdown");
        }
        catch (Exception ex)
        {
          logger.debug("sendMessages() error : " + ex.getMessage());
        }
      }
    }
}