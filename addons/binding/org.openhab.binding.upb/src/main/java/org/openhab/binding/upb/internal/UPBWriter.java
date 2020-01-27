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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.openhab.binding.upb.internal.UPBReader.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used to write data to the UPB modem.
 *
 * @author Chris Van Orman, Dustin Gerold
 * @since 1.9.0
 */
public class UPBWriter 
{
    /**
     * Time in milliseconds to wait for an ACK from the modem after writing a
     * message.
     */
    private static long ACK_TIMEOUT = 1000;

    private final Logger logger = LoggerFactory.getLogger(UPBWriter.class);

    /**
     * Asynchronous queue for writing data to the UPB modem.
     */
    private ExecutorService executor = new ThreadPoolExecutor(0, 1, 1000, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>());

    /**
     * Asynchronous queue for writing retry data to the UPB modem.
     */
    private ExecutorService retryExecutor = new ThreadPoolExecutor(0, 2, 1000, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>());

    /**
     * The UPB modem's OutputStream.
     */
    private OutputStream outputStream;
    
    /**
     * Lock for thread safe operations.
     */
    private ReentrantLock lock = new ReentrantLock();

    /**
     * UPBReader that is monitoring the modem's InputStream.
     */
    private UPBReader upbReader;

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
        this.outputStream = outputStream;
        this.upbReader = upbReader;
    }

    /**
     * Queues a message to be written to the modem.
     *
     * @param message
     *            the message to be written.
     */
    public void queueMessage(MessageBuilder message) 
    {
        String data = message.build();
        logger.debug("Queueing message {}.", data);
        executor.execute(new FutureMessage(new Message(data.getBytes(), message.getPriority())));
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
        executor.shutdownNow();
        retryExecutor.shutdownNow();

        try 
        {
          if (outputStream != null)
          {
            outputStream.close();
          }
        } 
        catch (IOException e) 
        {
          logger.error("UPBWriter shutdown ioerror : " + e.getMessage());
        }
      }
      catch (Exception e) 
      {
        logger.error("UPBWriter shutdown error : " + e.getMessage());
      }

      logger.debug("UPBWriter shutdown");
    }

    private static class FutureMessage extends FutureTask<FutureMessage> implements Comparable<FutureMessage> 
    {
        private Message message;

        public FutureMessage(Message message) 
        {
            super(message, null);

            this.message = message;
        }

        @Override
        public int compareTo(FutureMessage o) 
        {
            return message.priority.getValue() - o.message.priority.getValue();
        }
    }

    private enum Timing 
    {
      NORMAL, DELAYED;
    } 

    /**
     * {@link Runnable} implementation used to write data to the UPB modem.
     *
     * @author Chris Van Orman
     *
     */
    private class Message implements Runnable, Listener 
    {
      private boolean waitingOnAck = true;
      private boolean ackReceived = false;
      private byte[] data;
      private UPBMessage.Priority priority;
      private int intRetryCount = 4;
      Timing messageTiming;

      private Message(byte[] data, UPBMessage.Priority priority) 
      {
        this.data = data;
        this.priority = priority;
        messageTiming = Timing.NORMAL;
      }

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
          logger.error("Message ackReceived error : " + e.getMessage());
        }
      }

      private synchronized boolean waitForAck() 
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

      /**
       * {@inheritDoc}
       */
      @Override
      public void messageReceived(UPBMessage message) 
      {
        try
        {
          switch (message.getType()) 
          {
            case BUSY:

            case NAK:
              ackReceived(false);
              break;

            case ACK:
              ackReceived(true);
              break;

            default:
          }
        }
        catch (Exception e) 
        {
          logger.error("Message messageReceived error : " + e.getMessage());
        }
      }

      private synchronized void waitForDelay(Long lngDelay) 
      {
        try 
        {
          wait(lngDelay);
        } 
        catch (InterruptedException e) 
        {
          Thread.currentThread().interrupt();
          logger.error("Message waitForDelay error : " + e.getMessage());
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void run() 
      {
        try 
        {
          upbReader.addListener(this);
          long lngPriorityDelay = Long.valueOf(priority.getDelay());
          boolean boolRetryFailed = false;
          long lngMinute = 60000, lngRetryDelay = 60000;

          do 
          {
            ackReceived = false;
            waitingOnAck = true;
            intRetryCount = intRetryCount - 1;

            if (messageTiming == Timing.DELAYED)
            {
              switch (intRetryCount) 
              {
                case 0:
                  boolRetryFailed = true;
                  break;

                case 1:
                  lngRetryDelay = lngMinute * 15;
                  break;

                case 2:
                  lngRetryDelay = lngMinute * 15;
                  break;

                case 3:
                  lngRetryDelay = lngMinute * 15;
                  break;

                case 4:
                  lngRetryDelay = lngMinute * 10;
                  break;

                case 5:
                  lngRetryDelay = lngMinute * 5;
                  break;

                default:
              }

              if (boolRetryFailed)
              {
                logger.debug("Did not receive acknowledgement from UPB device after all delayed attempts, no more attempts will be made!");
                break;
              }

              logger.debug("Waiting " + lngRetryDelay + " ms before sending delayed UPB message");
               
              waitForDelay(lngRetryDelay);
            }
 
            if (intRetryCount == 0)
              break;

            logger.debug("Writing bytes: {}", new String(data));

            lock.lock();
            
            try
            {
              outputStream.write(0x14);
              outputStream.write(data);
              outputStream.write(0x0d);
            }
            catch (Exception e)
            {
              logger.debug("Message lock error : " + e.getMessage());
            }
            finally
            {
              lock.unlock();
            }

            if (lngPriorityDelay > 0) 
            {
              waitForDelay(lngPriorityDelay);
            }
          } while (!waitForAck());

          if ((ackReceived == false) & (messageTiming == Timing.NORMAL))
          {
            logger.debug("Sending message to delayed queue");
            messageTiming = Timing.DELAYED;
            intRetryCount = 6;
            retryExecutor.execute(new FutureMessage(this));
          }
        }
        catch (Exception e) 
        {
          logger.error("Message run error : " + e.getMessage());
        } 
        finally 
        {
          upbReader.removeListener(this);
        }
      }
    }
}