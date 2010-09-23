/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.bpm;

import org.mule.MessageExchangePattern;
import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.config.ConfigurationException;
import org.mule.api.lifecycle.Disposable;
import org.mule.api.lifecycle.Initialisable;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.config.i18n.MessageFactory;
import org.mule.module.bpm.BPMS;
import org.mule.module.bpm.MessageService;
import org.mule.module.bpm.Process;
import org.mule.module.client.MuleClient;
import org.mule.transport.AbstractConnector;
import org.mule.util.StringUtils;

import java.util.Map;

/**
 * The BPM provider allows Mule events to initiate and/or advance processes in an
 * external or embedded Business Process Management System (BPMS). It also allows
 * executing processes to generate Mule events.
 * 
 * @deprecated It is recommended to configure BPM as a component rather than a transport for 3.x
 */
public class ProcessConnector extends AbstractConnector implements MessageService
{
    /** The underlying BPMS */
    protected BPMS bpms;

    /** This field will be used to correlate messages with processes. */
    protected String processIdField;

    /**
     * The global receiver allows an endpoint of type "bpm://*" to receive any
     * incoming message to the BPMS, regardless of the process. If this is false, the
     * process name must be specified for each endpoint, e.g. "bpm://MyProcess" will
     * only receive messages for the process "MyProcess".
     */
    protected boolean allowGlobalReceiver = false;

    public static final String PROTOCOL = "bpm";
    public static final String GLOBAL_RECEIVER = PROTOCOL + "://*";

    private MuleClient muleClient = null;

    public ProcessConnector(MuleContext context)
    {
        super(context);
    }    
    
    public String getProtocol()
    {
        return PROTOCOL;
    }

    protected void doInitialise() throws InitialisationException
    {
        try
        {
            if (bpms == null)
            {
                bpms = createBpms();
            }
            if (bpms == null)
            {
                throw new ConfigurationException(
                    MessageFactory.createStaticMessage("The bpms property must be set for this connector."));
            }

            if (bpms instanceof Initialisable)
            {
                ((Initialisable) bpms).initialise();
            }
            
            // Set a callback so that the BPMS may generate messages within Mule.
            bpms.setMessageService(this);
            
            // The MuleClient is used as a global dispatcher.  
            // TODO MULE-1221 It would be cleaner to use something like the dynamic:// transport
            if (muleClient == null)
            {
                muleClient = new MuleClient(muleContext);
            }
        }
        catch (Exception e)
        {
            throw new InitialisationException(e, this);
        }
    }

    /** 
     * Override this method to create the BPMS upon initialization of the connector.
     * @return an initialized BPMS
     */
    protected BPMS createBpms() throws Exception
    {
        return null;
    }
    
    protected void doDispose()
    {
        if (bpms instanceof Disposable)
        {
            ((Disposable) bpms).dispose();
        }
    }

    protected void doConnect() throws Exception
    {
        // template method
    }

    protected void doDisconnect() throws Exception
    {
        // template method
    }

    protected void doStart() throws MuleException
    {
        // template method
    }

    protected void doStop() throws MuleException
    {
        // template method
    }

    /**
     * This method looks for a receiver based on the process name and ID. It searches
     * iteratively from the narrowest scope (match process name and ID) to the widest
     * scope (match neither - global receiver) possible.
     * 
     * @return ProcessMessageReceiver or null if no match is found
     */
    public ProcessMessageReceiver lookupReceiver(String processName, Object processId)
    {
        ProcessMessageReceiver receiver = (ProcessMessageReceiver)lookupReceiver(toUrl(processName, processId));
        if (receiver == null)
        {
            receiver = (ProcessMessageReceiver)lookupReceiver(toUrl(processName, null));
        }
        if (receiver == null)
        {
            receiver = (ProcessMessageReceiver)lookupReceiver(toUrl(null, null));
        }
        return receiver;
    }

    /**
     * Generate a URL based on the process name and ID such as "bpm://myProcess/2342"
     * If the parameters are missing, and <code>allowGlobalReceiver</code> is true,
     * the GLOBAL_RECEIVER is returned.
     */
    public String toUrl(String processName, Object processId)
    {
        String url = getProtocol() + "://";
        if (StringUtils.isNotEmpty(processName))
        {
            url += processName;
            if (processId != null)
            {
                url += "/" + processId;
            }
        }
        else if (isAllowGlobalReceiver())
        {
            return GLOBAL_RECEIVER;
        }
        else
        {
            throw new IllegalArgumentException(
                "No valid URL could be created for the given process name and ID: processName = " + processName + ", processId = " + processId);
        }
        return url;
    }

    public MuleMessage generateMessage(String endpoint,
                                      Object payloadObject,
                                      Map messageProperties,
                                      MessageExchangePattern mep) throws Exception
    {
        String processName = (String)messageProperties.get(Process.PROPERTY_PROCESS_TYPE);
        Object processId = messageProperties.get(Process.PROPERTY_PROCESS_ID);

        // Look up a receiver for this process.
        ProcessMessageReceiver receiver = lookupReceiver(processName, processId);
        if (receiver == null)
        {
            throw new ConfigurationException(MessageFactory
                .createStaticMessage("No corresponding receiver found for processName = " + processName
                                + ", processId = " + processId));
        }

        logger.debug("Generating Mule message for process name = " + processName + " id = " + processId + ", synchronous = " + mep.hasResponse());
        
        if (mep.hasResponse())
        {
            // Send the process-generated Mule message synchronously.
            return receiver.generateSynchronousEvent(endpoint, payloadObject, messageProperties);
        }
        else
        {
            // Dispatch the process-generated Mule message asynchronously.
            receiver.generateAsynchronousEvent(endpoint, payloadObject, messageProperties);
            return null;
        }
    }

    // //////////////////////////////////////////////////////////////////////////
    // Getters and Setters
    // //////////////////////////////////////////////////////////////////////////

    public BPMS getBpms()
    {
        return bpms;
    }

    public void setBpms(BPMS bpms)
    {
        this.bpms = bpms;
    }

    public MuleClient getMuleClient()
    {
        return muleClient;
    }

    public boolean isAllowGlobalReceiver()
    {
        return allowGlobalReceiver;
    }

    public void setAllowGlobalReceiver(boolean allowGlobalReceiver)
    {
        this.allowGlobalReceiver = allowGlobalReceiver;
    }

    public String getProcessIdField()
    {
        return processIdField;
    }

    public void setProcessIdField(String processIdField)
    {
        this.processIdField = processIdField;
    }
}
