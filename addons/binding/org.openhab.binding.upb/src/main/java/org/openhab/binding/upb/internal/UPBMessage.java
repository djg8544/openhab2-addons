/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.binding.upb.internal;
import java.util.Arrays;
import javax.xml.bind.DatatypeConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Model for a message sent or received from a UPB modem.
 *
 * @author Chris Van Orman, Dustin Gerold
 * @since 1.9.0
 */
public class UPBMessage 
{
  private static Type[] allCommandTypes;
  private static Command[] allDeviceCommands;

  public UPBMessage()
  {
    allCommandTypes = Type.values();
    allDeviceCommands = Command.values();
  }

    /**
     * An enum of possible message priorities.
     * 
     * @author Chris
     *
     */
    public enum Priority 
    {
        LOW(0, 1000), NORMAL(1, 0);

        private int value;
        private int delay;

        private Priority(int value, int delay) 
        {
            this.value = value;
            this.delay = delay;
        }

        /**
         * @return the value
         */
        public int getValue() 
        {
            return value;
        }

        /**
         * @return the delay
         */
        public int getDelay() 
        {
            return delay;
        }
    }

    /**
     * An enum of possible commands.
     *
     * @author Chris Van Orman
     *
     */
    public enum Command 
    {
        ACTIVATE(0x20),
        DEACTIVATE(0x21),
        GOTO(0x22),
        START_FADE(0x23),
        STOP_FADE(0x24),
        BLINK(0x25),
        REPORT_STATE(0x30),
        STORE_STATE(0x31),
        DEVICE_STATE(0x86),
        NONE(0x00);

        private int intDecimalValue = 0;

        /**
         * Gets the protocol byte code for this Command.
         *
         * @return the protocol byte code.
         */
        public int getDecimalValue() 
        {
          return intDecimalValue;
        }

        public byte toByte()
        {
          byte commandByte = (byte) intDecimalValue;
          return commandByte;
        }

        /**
         * Converts a byte value into a Command.
         *
         * @param value
         *            the byte value.
         * @return the Command that is represented by the given byte value.
         */
        private Command(int intDecimalValue) 
        {
          this.intDecimalValue = intDecimalValue;
        }
    }

    /**
     * An enum of possible modem response types.
     *
     * @author Chris Van Orman
     *
     */
    public enum Type 
    {
        ACCEPT("PA"),
        BUSY("PB"),
        ERROR("PE"),
        ACK("PK"),
        NAK("PN"),
        MESSAGE_REPORT("PU"),
        NONE("");

        private final String label;

        public String getLabel()
        {
          return label;
        }

        private Type(String label) 
        {
          this.label = label;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(UPBMessage.class);

    /**
     * Converts a hex string into a {@link UPBMessage}.
     *
     * @param commandString
     *            the string as returned by the modem.
     * @return a new UPBMessage.
     */
    public static UPBMessage fromString(String commandString) 
    {
      UPBMessage command = new UPBMessage();
 
      try
      {
        String typeString = commandString.substring(0, 2);
        Type type = Type.NONE;
        boolean boolCommandFound = false;
        String strCommandType;

        for (Type commandType : allCommandTypes) 
        { 
          strCommandType = commandType.getLabel();

          if (strCommandType.equals(typeString))
          {
            type = commandType;
            break;
          }
        }

        command.setType(type);

        if (commandString.length() > 2) 
        {
          byte[] data = DatatypeConverter.parseHexBinary(commandString.substring(2));
          command.getControlWord().setBytes(data[1], data[0]);
          int index = 2;
          command.setNetwork(data[index++]);
          command.setDestination(data[index++]);
          command.setSource(data[index++]);

          int commandCode = data[index++] & 0xFF;

          for (Command deviceCommand : allDeviceCommands) 
          { 
            if (deviceCommand.getDecimalValue() == commandCode)
            {
              command.setCommand(deviceCommand);
              boolCommandFound = true;
              break;
            }
          }

           if (!boolCommandFound)
           {
             command.setCommand(Command.NONE);
           }

           if (index < data.length - 1) 
           {
             command.setArguments(Arrays.copyOfRange(data, index, data.length - 1));
           }
         }
       } 
       catch (Exception e) 
       {
         logger.error("UPBMessage.fromString() Error when parsing message : " + commandString + ". Error : " + e.getMessage());
       }

       return command;
    }

    private Type type;
    private ControlWord controlWord = new ControlWord();
    private byte network;
    private byte destination;
    private byte source;

    private Command command = Command.NONE;
    private byte[] arguments;

    /**
     * @return the type
     */
    public Type getType() 
    {
        return type;
    }

    /**
     * @param type
     *            the type to set
     */
    public void setType(Type type) 
    {
        this.type = type;
    }

    /**
     * @return the controlWord
     */
    public ControlWord getControlWord() 
    {
        return controlWord;
    }

    /**
     * @param controlWord
     *            the controlWord to set
     */
    public void setControlWord(ControlWord controlWord) 
    {
        this.controlWord = controlWord;
    }

    /**
     * @return the network
     */
    public byte getNetwork() 
    {
        return network;
    }

    /**
     * @param network
     *            the network to set
     */
    public void setNetwork(byte network) 
    {
        this.network = network;
    }

    /**
     * @return the destination
     */
    public byte getDestination() 
    {
        return destination;
    }

    /**
     * @param destination
     *            the destination to set
     */
    public void setDestination(byte destination) 
    {
        this.destination = destination;
    }

    /**
     * @return the source
     */
    public byte getSource() 
    {
        return source;
    }

    /**
     * @param source
     *            the source to set
     */
    public void setSource(byte source) 
    {
        this.source = source;
    }

    /**
     * @return the command
     */
    public Command getCommand() 
    {
        return command;
    }

    /**
     * @param command
     *            the command to set
     */
    public void setCommand(Command command) 
    {
        this.command = command;
    }

    /**
     * @return the arguments
     */
    public byte[] getArguments() 
    {
        return arguments;
    }

    /**
     * @param arguments
     *            the arguments to set
     */
    public void setArguments(byte[] arguments) 
    {
        this.arguments = arguments;
    }
}