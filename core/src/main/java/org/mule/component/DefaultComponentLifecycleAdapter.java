/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.component;

import org.mule.DefaultMuleEventContext;
import org.mule.RequestContext;
import org.mule.api.DefaultMuleException;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleEventContext;
import org.mule.api.MuleException;
import org.mule.api.component.InterfaceBinding;
import org.mule.api.component.JavaComponent;
import org.mule.api.component.LifecycleAdapter;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.lifecycle.Disposable;
import org.mule.api.lifecycle.Initialisable;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.lifecycle.Startable;
import org.mule.api.lifecycle.Stoppable;
import org.mule.api.model.EntryPointResolverSet;
import org.mule.config.i18n.CoreMessages;
import org.mule.config.i18n.MessageFactory;
import org.mule.model.resolvers.LegacyEntryPointResolverSet;
import org.mule.model.resolvers.NoSatisfiableMethodsException;
import org.mule.model.resolvers.TooManySatisfiableMethodsException;
import org.mule.registry.JSR250ValidatorProcessor;
import org.mule.util.ClassUtils;
import org.mule.util.annotation.AnnotationMetaData;
import org.mule.util.annotation.AnnotationUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * <code>DefaultComponentLifecycleAdapter</code> is a default implementation of
 * {@link LifecycleAdapter} for use with {@link JavaComponent} that expects component
 * instances to implement Mule lifecycle interfaces in order to receive lifecycle. Lifecycle interfaces supported are -
 * <ul>
 * <li>{@link org.mule.api.lifecycle.Initialisable}</li>
 * <li>{@link org.mule.api.lifecycle.Startable}</li>
 * <li>{@link org.mule.api.lifecycle.Stoppable}</li>
 * <li>{@link org.mule.api.lifecycle.Disposable}</li>
 * </ul>
 *  This implementation also supports JSR-250 lifecycle annotations
 *  {@link javax.annotation.PostConstruct} (for initialisation) and/or {@link javax.annotation.PreDestroy}
 * (for disposal of the object). Only one of each annotation can be used per component object.
 *
 * @see org.mule.registry.JSR250ValidatorProcessor for details about the rules for using JSR-250 lifecycle annotations
 */
public class DefaultComponentLifecycleAdapter implements LifecycleAdapter
{
    /**
     * logger used by this class
     */
    protected static final Log logger = LogFactory.getLog(DefaultComponentLifecycleAdapter.class);

    protected Object componentObject;

    protected JavaComponent component;
    protected EntryPointResolverSet entryPointResolver;
    protected FlowConstruct flowConstruct;

    protected boolean isInitialisable = false;
    protected boolean isStartable = false;
    protected boolean isStoppable = false;
    protected boolean isDisposable = false;

    protected Method initMethod;
    protected Method disposeMethod;

    private boolean started = false;
    private boolean disposed = false;

    protected MuleContext muleContext;

    public DefaultComponentLifecycleAdapter(Object componentObject,
                                            JavaComponent component,
                                            FlowConstruct flowConstruct,
                                            MuleContext muleContext) throws MuleException
    {
        if (muleContext == null)
        {
            throw new IllegalStateException("No muleContext provided");
        }
        if (componentObject == null)
        {
            throw new IllegalArgumentException("POJO Service cannot be null");
        }

        if (entryPointResolver == null)
        {
            entryPointResolver = new LegacyEntryPointResolverSet();
        }
        this.componentObject = componentObject;
        this.component = component;
        this.flowConstruct = flowConstruct;

        // save a ref for later disposal call
        this.muleContext = muleContext;
        setLifecycleFlags();
        configureBinding();
    }

    public DefaultComponentLifecycleAdapter(Object componentObject,
                                            JavaComponent component,
                                            FlowConstruct flowConstruct,
                                            EntryPointResolverSet entryPointResolver, MuleContext muleContext) throws MuleException
    {

        this(componentObject, component, flowConstruct, muleContext);
        this.entryPointResolver = entryPointResolver;
    }

    protected void setLifecycleFlags()
    {
        Object object = componentObject;
        initMethod = findInitMethod(object);
        disposeMethod = findDisposeMethod(object);
        isInitialisable = initMethod!=null;
        isDisposable = disposeMethod!=null;
        isStartable = Startable.class.isInstance(object);
        isStoppable = Stoppable.class.isInstance(object);
    }

    protected Method findInitMethod(Object object)
    {
        if(object instanceof Initialisable)
        {
            try
            {
                return object.getClass().getMethod(Initialisable.PHASE_NAME);
            }
            catch (NoSuchMethodException e)
            {
                //ignore
            }
        }

        List<AnnotationMetaData> metaData = AnnotationUtils.getMethodAnnotations(object.getClass(), PostConstruct.class);
        if (metaData.size() == 0)
        {
            return null;
        }
        else if(metaData.size() > 1)
        {
            throw new IllegalArgumentException(CoreMessages.objectHasMoreThanOnePostConstructAnnotation(object.getClass()).getMessage());
        }
        else
        {
            Method m = (Method) metaData.get(0).getMember();
            new JSR250ValidatorProcessor().validateLifecycleMethod(m);
            return m;
        }
    }

    protected Method findDisposeMethod(Object object)
    {
        if (object instanceof Disposable)
        {
            try
            {
                return object.getClass().getMethod(Disposable.PHASE_NAME);
            }
            catch (NoSuchMethodException e)
            {
                //ignore
            }
        }

        List<AnnotationMetaData> metaData = AnnotationUtils.getMethodAnnotations(object.getClass(), PreDestroy.class);
        if (metaData.size() == 0)
        {
            return null;
        }
        else if (metaData.size() > 1)
        {
            throw new IllegalArgumentException(CoreMessages.objectHasMoreThanOnePreDestroyAnnotation(object.getClass()).getMessage());
        }
        else
        {
            Method m = (Method) metaData.get(0).getMember();
            new JSR250ValidatorProcessor().validateLifecycleMethod(m);
            return m;
        }
    }

    /**
     * Propagates initialise() life-cycle to component object implementations if they
     * implement the mule {@link Initialisable} interface.
     * <p/>
     * <b>NOTE:</b> It is up to component implementations to ensure their implementation of
     * <code>initialise()</code> is thread-safe.
     */
    public void initialise() throws InitialisationException
    {
        if (isInitialisable)
        {
            try
            {
                initMethod.invoke(componentObject);
            }
            catch (IllegalAccessException e)
            {
                throw new InitialisationException(e, this);
            }
            catch (InvocationTargetException e)
            {
                throw new InitialisationException(e.getTargetException(), this);
            }
        }
    }

    /**
     * Propagates start() life-cycle to component object implementations if they
     * implement the mule {@link Startable} interface. NOT: It is up to component
     * implementations to ensure their implementation of start() is thread-safe.
     */
    public void start() throws MuleException
    {
        if (isStartable)
        {
            try
            {
                ((Startable) componentObject).start();
                started = true;
            }
            catch (Exception e)
            {
                throw new DefaultMuleException(CoreMessages.failedToStart("Service: "
                                                                          + flowConstruct.getName()), e);
            }
        }
        else
        {
            started = true;
        }
    }

    /**
     * Propagates stop() life-cycle to component object implementations if they
     * implement the mule {@link Stoppable} interface. NOT: It is up to component
     * implementations to ensure their implementation of stop() is thread-safe.
     */
    public void stop() throws MuleException
    {
        if (isStoppable)
        {
            try
            {
                ((Stoppable) componentObject).stop();
                started = false;
            }
            catch (Exception e)
            {
                throw new DefaultMuleException(CoreMessages.failedToStop("Service: "
                                                                         + flowConstruct.getName()), e);
            }
        }
        else
        {
            started = false;
        }
    }

    /**
     * Propagates dispose() life-cycle to component object implementations if they
     * implement the mule {@link Disposable} interface. NOT: It is up to component
     * implementations to ensure their implementation of dispose() is thread-safe.
     */
    public void dispose()
    {
        try
        {
            if (isDisposable)
            {
                // make sure we haven't lost the reference to the object
                Object o = componentObject;
                if (o != null)
                {
                    try
                    {
                        disposeMethod.invoke(o);
                    }
                    catch (InvocationTargetException e)
                    {
                        //unwrap
                        throw e.getTargetException();
                    }
                }
            }
            componentObject = null;

        }
        catch (Throwable e)
        {
            logger.error("failed to dispose: " + flowConstruct.getName(), e);
        }
        disposed = true;
    }

    /**
     * @return true if the service has been started
     */
    public boolean isStarted()
    {
        return started;
    }

    /**
     * @return whether the service managed by this lifecycle has been disposed
     */
    public boolean isDisposed()
    {
        return disposed;
    }

    public Object invoke(MuleEvent event) throws MuleException
    {
        // Invoke method
        MuleEventContext eventContext = new DefaultMuleEventContext(event);
        Object result;
        try
        {
            if (componentObject == null)
            {
                throw new ComponentException(MessageFactory.createStaticMessage("componentObject is null"), RequestContext.getEvent(), component);
            }
            // Use the overriding entrypoint resolver if one is set
            if (component.getEntryPointResolverSet() != null)
            {
                result = component.getEntryPointResolverSet().invoke(componentObject, eventContext);
            }
            else
            {
                result = entryPointResolver.invoke(componentObject, eventContext);
            }
        }
        catch (Exception e)
        {
            throw new ComponentException(RequestContext.getEvent(), component, e);
        }

        return result;
    }

    protected void configureBinding() throws MuleException
    {
        // Initialise the nested router and bind the endpoints to the methods using a
        // Proxy
        if (component.getInterfaceBindings() != null)
        {
            Map<Class<?>, Object> bindings = new HashMap<Class<?>, Object>();
            for (InterfaceBinding interfaceBinding : component.getInterfaceBindings())
            {
                Object proxy = bindings.get(interfaceBinding.getInterface());

                if (proxy == null)
                {
                    // Create a proxy that implements this interface
                    // and just routes away using a mule client
                    // ( using the high level Mule client is probably
                    // a bit agricultural but this is just POC stuff )
                    proxy = interfaceBinding.createProxy(componentObject);
                    bindings.put(interfaceBinding.getInterface(), proxy);

                    // Now lets set the proxy on the Service object
                    Method setterMethod;

                    List methods = ClassUtils.getSatisfiableMethods(componentObject.getClass(),
                            new Class[]{interfaceBinding.getInterface()}, true, false, null);
                    if (methods.size() == 1)
                    {
                        setterMethod = (Method) methods.get(0);
                    }
                    else if (methods.size() > 1)
                    {
                        throw new TooManySatisfiableMethodsException(componentObject.getClass(),
                                new Class[]{interfaceBinding.getInterface()});
                    }
                    else
                    {
                        throw new NoSatisfiableMethodsException(componentObject.getClass(),
                                new Class[]{interfaceBinding.getInterface()});
                    }

                    try
                    {
                        setterMethod.invoke(componentObject, proxy);
                    }
                    catch (Exception e)
                    {
                        throw new InitialisationException(CoreMessages.failedToSetProxyOnService(interfaceBinding,
                                componentObject.getClass()), e, this);
                    }
                }
                else
                {
                    BindingInvocationHandler handler = (BindingInvocationHandler) Proxy.getInvocationHandler(proxy);
                    handler.addRouterForInterface(interfaceBinding);
                }
            }
        }
    }
}