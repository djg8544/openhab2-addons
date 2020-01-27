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
import java.io.InputStream;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import gnu.io.SerialPort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.TooManyListenersException;

import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class monitors the input stream of a UPB modem. This is done
 * asynchronously. When messages are received, they are broadcast to all
 * subscribed {@link Listener listeners}.
 *
 * @author Chris Van Orman, Dustin Gerold
 * @since 1.9.0
 */
public class UPBReader implements SerialPortEventListener 
{

    /**
     * Listener class for handling received messages. A listener can be added by
     * calling {@link UPBReader#addListener(Listener)}.
     *
     * @author cvanorman
     *
     */
    public interface Listener 
    {

        /**
         * Called whenever a message has been received from the UPB modem.
         *
         * @param message
         *            the message that was received.
         */
        void messageReceived(UPBMessage message);
    }

    private final Logger logger = LoggerFactory.getLogger(UPBReader.class);

    private Collection<Listener> listeners = new LinkedHashSet<>();
    private byte[] buffer = new byte[512];
    private int bufferLength = 0;
    private InputStream inputStream;
    private Thread thread;
    private SerialPort serialPort;

    /**
     * Instantiates a new {@link UPBReader}.
     *
     * @param inputStream
     *            the inputStream from the UPB modem.
     */
    public UPBReader(SerialPort tmpSerialPort) 
    {
      try
      {
        serialPort = tmpSerialPort;
        inputStream = serialPort.getInputStream();

        serialPort.addEventListener(this);
        serialPort.notifyOnDataAvailable(true);
      }
      catch (IOException e) 
      {
          logger.error("UPBReader error : " + e.getMessage());
      }
      catch (TooManyListenersException e) 
      {
          logger.error("UPBReader error : " + e.getMessage());
      }
    }

    /**
     * Subscribes the listener to any future message events.
     *
     * @param listener
     *            the listener to add.
     */
    public synchronized void addListener(Listener listener) 
    {
        listeners.add(listener);
    }

    /**
     * Removes the listener from further messages.
     *
     * @param listener
     *            the listener to remove.
     */
    public synchronized void removeListener(Listener listener) 
    {
        listeners.remove(listener);
    }

    /**
     * Adds data to the buffer.
     *
     * @param data
     *            the data to add.
     * @param length
     *            the length of data to add.
     */
    private void addData(byte[] data, int length) 
    {
      try
      {
        if (bufferLength + length > buffer.length) 
        {
            // buffer overflow discard entire buffer
            bufferLength = 0;
        }

        System.arraycopy(data, 0, buffer, bufferLength, length);

        bufferLength += length;

        interpretBuffer();
      }
      catch (Exception e) 
      {
          logger.error("sddData error : " + e.getMessage());
      }
    }

    /**
     * Shuts the reader down.
     */
    public void shutdown() 
    {
      try
      {
        serialPort.notifyOnDataAvailable(false);
        serialPort.removeEventListener();

        try 
        {
          if (inputStream != null)
          {
            inputStream.close();
          }
        } 
        catch (IOException e) 
        {
          logger.error("UPBReader shutdown ioerror : " + e.getMessage());
        }
      }
      catch (Exception e) 
      {
        logger.error("UPBReader shutdown error : " + e.getMessage());
      }
    
      logger.debug("UPBReader shutdown.");
    }

    private int findMessageLength(byte[] buffer, int bufferLength) 
    {
      int messageLength = ArrayUtils.INDEX_NOT_FOUND;

      try
      {        
        for (int i = 0; i < bufferLength; i++) 
        {
            if (buffer[i] == 13) 
            {
                messageLength = i;
                break;
            }
        }
      }
      catch (Exception e) 
      {
        logger.error("findMessageLength error : " + e.getMessage());
      }
        return messageLength;
    }

    /**
     * Attempts to interpret any messages that may be contained in the buffer.
     */
    private void interpretBuffer() 
    {
      int messageLength = findMessageLength(buffer, bufferLength);
      
      try
      {
        while (messageLength != ArrayUtils.INDEX_NOT_FOUND) 
        {
            String message = new String(Arrays.copyOfRange(buffer, 0, messageLength));
            logger.debug("UPB Message: {}", message);

            int remainingBuffer = bufferLength - messageLength - 1;

            if (remainingBuffer > 0) 
            {
                System.arraycopy(buffer, messageLength + 1, buffer, 0, remainingBuffer);
            }

            bufferLength = remainingBuffer;

            notifyListeners(UPBMessage.fromString(message));

            messageLength = findMessageLength(buffer, bufferLength);
        }
      }
      catch (Exception e) 
      {
        logger.error("interpretBuffer error : " + e.getMessage());
      }
    }

    private synchronized void notifyListeners(UPBMessage message) 
    {
        for (Listener l : new ArrayList<>(listeners)) 
        {
            l.messageReceived(message);
        }
    }

    public void serialEvent(SerialPortEvent event) 
    {
      try
      {
        switch(event.getEventType()) 
        {
          case SerialPortEvent.BI:
            logger.debug("SerialPortEvent.BI occurred");
            break;

          case SerialPortEvent.OE:
            logger.debug("SerialPortEvent.OE occurred");
            break;

          case SerialPortEvent.FE:
            logger.debug("SerialPortEvent.FE occurred");
            break;

          case SerialPortEvent.PE:
            logger.debug("SerialPortEvent.PE occurred");
            break;

          case SerialPortEvent.CD:
            logger.debug("SerialPortEvent.CD occurred");
            break;

          case SerialPortEvent.CTS:
            logger.debug("SerialPortEvent.CTS occurred");
            break;

          case SerialPortEvent.DSR:
            logger.debug("SerialPortEvent.DSR occurred");
            break;

          case SerialPortEvent.RI:
            logger.debug("SerialPortEvent.RI occurred");
            break;

          case SerialPortEvent.OUTPUT_BUFFER_EMPTY:
            logger.debug("SerialPortEvent.OUTPUT_BUFFER_EMPTY occurred");
            break;

          case SerialPortEvent.DATA_AVAILABLE:
            byte[] readBuffer = new byte[32];
            int numBytes = 0;

            try 
            {
                while (inputStream.available() > 0) 
                {
                  numBytes = inputStream.read(readBuffer);

                  if (numBytes > 0) 
                  {
                    logger.debug("Received: {}", ArrayUtils.subarray(readBuffer, 0, numBytes));

                    addData(readBuffer, numBytes);
                  }
                }
            } 
            catch (IOException e) 
            {
               logger.error("SerialPortEvent error : " + e.getMessage());
            }

            break;
        }
      }
      catch (Exception e) 
      {
        logger.error("serialEvent error : " + e.getMessage());
      }
    }
}