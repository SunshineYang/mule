/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.registry;

import static org.apache.commons.lang3.exception.ExceptionUtils.getMessage;
import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_NOTIFICATION_HANDLER;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_REGISTRY;

import org.mule.runtime.api.exception.ErrorTypeRepository;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.i18n.I18nMessageFactory;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.transformer.Transformer;
import org.mule.runtime.core.api.util.StringUtils;
import org.mule.runtime.core.internal.lifecycle.LifecycleInterceptor;
import org.mule.runtime.core.internal.lifecycle.phases.NotInLifecyclePhase;
import org.mule.runtime.core.internal.registry.map.RegistryMap;
import org.mule.runtime.core.privileged.PrivilegedMuleContext;
import org.mule.runtime.core.privileged.endpoint.LegacyImmutableEndpoint;
import org.mule.runtime.core.privileged.exception.ErrorTypeLocator;
import org.mule.runtime.core.privileged.registry.InjectProcessor;
import org.mule.runtime.core.privileged.registry.RegistrationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.functors.InstanceofPredicate;

/**
 * Use the registryLock when reading/writing/iterating over the contents of the registry hashmap.
 *
 * @deprecated as of 3.7.0. Use {@link SimpleRegistry instead}.
 */
@Deprecated
public abstract class TransientRegistry extends AbstractRegistry {

  private final RegistryMap registryMap = new RegistryMap(logger);

  private List<InjectProcessor> injectProcessors = new ArrayList<>();

  public TransientRegistry(String id, MuleContext muleContext, LifecycleInterceptor lifecycleInterceptor) {
    super(id, muleContext, lifecycleInterceptor);
    putDefaultEntriesIntoRegistry();
  }

  private void putDefaultEntriesIntoRegistry() {
    injectProcessors.add(new RegistryProcessor(muleContext));
    injectProcessors.add(new LifecycleStateInjectorProcessor(getLifecycleManager().getState()));
    injectProcessors.add(new MuleContextProcessor(muleContext));

    Map<String, Object> processors = new HashMap<>();
    if (muleContext != null) {
      processors.put(OBJECT_REGISTRY, new DefaultRegistry(muleContext));
      processors.put(ErrorTypeRepository.class.getName(), muleContext.getErrorTypeRepository());
      processors.put(ErrorTypeLocator.class.getName(), ((PrivilegedMuleContext) muleContext).getErrorTypeLocator());
      processors.put(OBJECT_NOTIFICATION_HANDLER, ((PrivilegedMuleContext) muleContext).getNotificationManager());
    }
    processors.put("_muleLifecycleManager", getLifecycleManager());
    registryMap.putAll(processors);
  }

  @Override
  protected void doInitialise() throws InitialisationException {
    applyProcessors(lookupObjects(Transformer.class));
    applyProcessors(lookupObjects(LegacyImmutableEndpoint.class));
    applyProcessors(lookupObjects(Object.class));
  }

  @Override
  protected void doDispose() {
    disposeLostObjects();
    registryMap.clear();
  }

  private void disposeLostObjects() {
    for (Object obj : registryMap.getLostObjects()) {
      try {
        ((Disposable) obj).dispose();
      } catch (Exception e) {
        logger.warn("Can not dispose object. " + getMessage(e));
        if (logger.isDebugEnabled()) {
          logger.debug("Can not dispose object. " + getStackTrace(e));
        }
      }
    }
  }


  @Override
  public void registerObjects(Map<String, Object> objects) throws RegistrationException {
    if (objects == null) {
      return;
    }

    for (Map.Entry<String, Object> entry : objects.entrySet()) {
      registerObject(entry.getKey(), entry.getValue());
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Map<String, T> lookupByType(Class<T> type) {
    final Map<String, T> results = new HashMap<>();
    try {
      registryMap.lockForReading();

      for (Map.Entry<String, Object> entry : registryMap.entrySet()) {
        final Class<?> clazz = entry.getValue().getClass();
        if (type.isAssignableFrom(clazz)) {
          results.put(entry.getKey(), (T) entry.getValue());
        }
      }
    } finally {
      registryMap.unlockForReading();
    }

    return results;
  }

  @Override
  public <T> T lookupObject(String key) {
    return doGet(key);
  }

  @Override
  public <T> T lookupObject(Class<T> type) throws RegistrationException {
    return super.lookupObject(type);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Collection<T> lookupObjects(Class<T> returntype) {
    return (Collection<T>) registryMap.select(new InstanceofPredicate(returntype));
  }

  @Override
  public <T> Collection<T> lookupLocalObjects(Class<T> type) {
    // just delegate to lookupObjects since there's no parent ever
    return lookupObjects(type);
  }

  @Override
  public boolean isSingleton(String key) {
    return true;
  }

  /**
   * Will fire any lifecycle methods according to the current lifecycle without actually registering the object in the registry.
   * This is useful for prototype objects that are created per request and would clutter the registry with single use objects.
   *
   * @param object the object to process
   * @return the same object with lifecycle methods called (if it has any)
   * @throws MuleException if the registry fails to perform the lifecycle change for the object.
   */
  @Override
  public Object applyLifecycle(Object object) throws MuleException {
    getLifecycleManager().applyCompletedPhases(object);
    return object;
  }

  @Override
  public Object applyLifecycle(Object object, String phase) throws MuleException {
    getLifecycleManager().applyPhase(object, NotInLifecyclePhase.PHASE_NAME, phase);
    return object;
  }

  protected Object applyProcessors(Object object) {
    Object theObject = object;

    for (InjectProcessor processor : injectProcessors) {
      theObject = processor.process(theObject);
    }
    return theObject;
  }

  /**
   * Allows for arbitary registration of transient objects
   *
   * @param key
   * @param object
   */
  @Override
  public void registerObject(String key, Object object) throws RegistrationException {
    checkDisposed();
    if (StringUtils.isBlank(key)) {
      throw new RegistrationException(I18nMessageFactory.createStaticMessage("Attempt to register object with no key"));
    }

    if (logger.isDebugEnabled()) {
      logger.debug(String.format("registering key/object %s/%s", key, object));
    }

    logger.debug("applying processors");
    object = applyProcessors(object);
    if (object == null) {
      return;
    }

    doRegisterObject(key, object);
  }

  protected <T> T doGet(String key) {
    return registryMap.get(key);
  }

  protected void doRegisterObject(String key, Object object) throws RegistrationException {
    doPut(key, object);

    try {
      getLifecycleManager().applyCompletedPhases(object);
    } catch (MuleException e) {
      throw new RegistrationException(e);
    }
  }

  protected void doPut(String key, Object object) {
    registryMap.putAndLogWarningIfDuplicate(key, object);
  }

  protected void checkDisposed() throws RegistrationException {
    if (getLifecycleManager().isPhaseComplete(Disposable.PHASE_NAME)) {
      throw new RegistrationException(I18nMessageFactory
          .createStaticMessage("Cannot register objects on the registry as the context is disposed"));
    }
  }

  @Override
  protected Object doUnregisterObject(String key) throws RegistrationException {
    return registryMap.remove(key);
  }

  // /////////////////////////////////////////////////////////////////////////
  // Registry Metadata
  // /////////////////////////////////////////////////////////////////////////

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean isRemote() {
    return false;
  }

}
