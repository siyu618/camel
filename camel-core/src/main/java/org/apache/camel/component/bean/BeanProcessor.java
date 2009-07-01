/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.bean;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.impl.ServiceSupport;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.ServiceHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * A {@link Processor} which converts the inbound exchange to a method
 * invocation on a POJO
 *
 * @version $Revision$
 */
public class BeanProcessor extends ServiceSupport implements Processor {
    private static final transient Log LOG = LogFactory.getLog(BeanProcessor.class);

    private boolean multiParameterArray;
    private Method methodObject;
    private String method;
    private BeanHolder beanHolder;

    public BeanProcessor(Object pojo, BeanInfo beanInfo) {
        this(new ConstantBeanHolder(pojo, beanInfo));
    }

    public BeanProcessor(Object pojo, CamelContext camelContext, ParameterMappingStrategy parameterMappingStrategy) {
        this(pojo, new BeanInfo(camelContext, pojo.getClass(), parameterMappingStrategy));
    }

    public BeanProcessor(Object pojo, CamelContext camelContext) {
        this(pojo, camelContext, BeanInfo.createParameterMappingStrategy(camelContext));
    }

    public BeanProcessor(BeanHolder beanHolder) {
        this.beanHolder = beanHolder;
    }

    @Override
    public String toString() {
        String description = methodObject != null ? " " + methodObject : "";
        return "BeanProcessor[" + beanHolder + description + "]";
    }

    public void process(Exchange exchange) throws Exception {
        Object bean = beanHolder.getBean();
        exchange.setProperty(Exchange.BEAN_HOLDER, beanHolder);

        Processor processor = getProcessor();
        BeanInfo beanInfo = beanHolder.getBeanInfo();

        // do we have a custom adapter for this POJO to a Processor
        if (processor != null) {
            processor.process(exchange);
            return;
        }
        Message in = exchange.getIn();

        if (in.getHeader(Exchange.BEAN_MULTI_PARAMETER_ARRAY) == null) {
            in.setHeader(Exchange.BEAN_MULTI_PARAMETER_ARRAY, isMultiParameterArray());
        }

        BeanInvocation beanInvoke = in.getBody(BeanInvocation.class);
        if (beanInvoke != null) {
            beanInvoke.invoke(bean, exchange);
            return;
        }

        boolean isExplicitMethod = false;
        String prevMethod = null;
        MethodInvocation invocation;
        if (methodObject != null) {
            invocation = beanInfo.createInvocation(methodObject, bean, exchange);
        } else {
            // we just override the bean's invocation method name here
            if (ObjectHelper.isNotEmpty(method)) {
                prevMethod = in.getHeader(Exchange.BEAN_METHOD_NAME, String.class);
                in.setHeader(Exchange.BEAN_METHOD_NAME, method);
                isExplicitMethod = true;
            }
            invocation = beanInfo.createInvocation(bean, exchange);
        }
        if (invocation == null) {
            throw new IllegalStateException(
                "No method invocation could be created, no maching method could be found on: " + bean);
        } else {
            // set method name if not explicit given
            if (method == null) {
                method = invocation.getMethod().getName();
            }
        }
   
        Object value = null;
        try {
            value = invocation.proceed();
        } catch (InvocationTargetException e) {
            // lets unwrap the exception
            Throwable throwable = e.getCause();
            if (throwable instanceof Exception) {
                Exception exception = (Exception)throwable;
                throw exception;
            } else {
                Error error = (Error)throwable;
                throw error;
            }
        } finally {
            if (isExplicitMethod) {
                in.setHeader(Exchange.BEAN_METHOD_NAME, prevMethod);
            }
        }
        
        if (value != null) {
            if (exchange.getPattern().isOutCapable()) {
                // force out creating if not already created (as its lazy)
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Setting bean invocation result on the OUT message: " + value);
                }
                exchange.getOut().setBody(value);
                // propagate headers
                exchange.getOut().getHeaders().putAll(exchange.getIn().getHeaders());
            } else {
                // if not out then set it on the in
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Setting bean invocation result on the IN message: " + value);
                }
                exchange.getIn().setBody(value);
            }
        }
    }

    protected Processor getProcessor() {
        return beanHolder.getProcessor();
    }

    // Properties
    // -----------------------------------------------------------------------

    public Method getMethodObject() {
        return methodObject;
    }

    public void setMethodObject(Method methodObject) {
        this.methodObject = methodObject;
    }

    public String getMethod() {
        return method;
    }

    public boolean isMultiParameterArray() {
        return multiParameterArray;
    }

    public void setMultiParameterArray(boolean mpArray) {
        multiParameterArray = mpArray;
    }

    /**
     * Sets the method name to use
     */
    public void setMethod(String method) {
        this.method = method;
    }

    // Implementation methods
    //-------------------------------------------------------------------------
    protected void doStart() throws Exception {
        ServiceHelper.startService(getProcessor());
    }

    protected void doStop() throws Exception {
        ServiceHelper.stopService(getProcessor());
    }
}
