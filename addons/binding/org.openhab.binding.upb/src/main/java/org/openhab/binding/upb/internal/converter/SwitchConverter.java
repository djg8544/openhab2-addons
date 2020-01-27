/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.upb.internal.converter;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.upb.UPBBindingConstants;
import org.openhab.binding.upb.internal.UPBMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link StateChannelConverter} for the {@link UPBBindingConstants#CHANNEL_SWITCH} channel.
 *
 * @author Chris Van Orman, Dustin Gerold
 * @since 2.2.0
 */
class SwitchConverter implements StateChannelConverter 
{
  private final Logger logger = LoggerFactory.getLogger(SwitchConverter.class);

    @Override
    public State convert(UPBMessage message) 
    {
      byte level = 100;
      State newState = null;

      try
      {
        switch (message.getCommand()) 
        {
            case GOTO:
            case DEVICE_STATE:
            case ACTIVATE:

                if (message.getArguments() != null && message.getArguments().length > 0) 
                {
                    level = message.getArguments()[0];
                } 
                else 
                {
                    level = (byte) (message.getCommand() == UPBMessage.Command.ACTIVATE ? 100 : 0);
                }

                if (level == 0) 
                {
                    newState = OnOffType.OFF;
                } 
                else 
                {
                    newState = OnOffType.ON;
                }

                break;

            case DEACTIVATE:
                newState = OnOffType.OFF;
                break;

            default:
                break;
        }
      }
      catch (Exception e)
      {
        logger.error("SwitchConverter convert error : " + e.getMessage());
      }

      return newState;
    }
}