/*
 * $Id: TextRenderer.java,v 1.13 2005/11/29 16:20:16 rlubke Exp $
 */

/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the License at
 * https://javaserverfaces.dev.java.net/CDDL.html or
 * legal/CDDLv1.0.txt. 
 * See the License for the specific language governing
 * permission and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at legal/CDDLv1.0.txt.    
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * [Name of File] [ver.__] [Date]
 * 
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */

// TextRenderer.java

package com.sun.faces.systest.render;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.sun.faces.util.MessageFactory;
import com.sun.faces.util.Util;

import javax.faces.component.UIComponent;
import javax.faces.component.UIInput;
import javax.faces.component.UIOutput;
import javax.faces.component.UIViewRoot;
import javax.faces.component.ValueHolder;
import javax.faces.context.FacesContext;
import javax.faces.context.ResponseWriter;
import javax.faces.convert.Converter;
import javax.faces.convert.ConverterException;
import javax.faces.render.Renderer;

import java.io.IOException;

/**
 * <B>TextRenderer</B> is a class that renders the current value of
 * <code>UIInput<code> or <code>UIOutput<code> component as a input field or
 * static text.
 */
public class TextRenderer extends Renderer {


    //
    // Protected Constants
    //
    // Log instance for this class
    protected static Log log = LogFactory.getLog(ButtonRenderer.class);

    //
    // Class Variables
    //

    //
    // Instance Variables
    //

    // Attribute Instance Variables


    // Relationship Instance Variables

    //
    // Constructors and Initializers    
    //

    public TextRenderer() {
        super();
    }

    //
    // Class methods
    //

    //
    // General Methods
    //

    //
    // Methods From Renderer
    //

    public void encodeBegin(FacesContext context, UIComponent component)
        throws IOException {
        if (context == null || component == null) {
            throw new NullPointerException(Util.getExceptionMessageString(
                Util.NULL_PARAMETERS_ERROR_MESSAGE_ID));
        }
    }

    public void encodeEnd(FacesContext context, UIComponent component)
        throws IOException {

        String currentValue = null;
        ResponseWriter writer = null;
        String styleClass = null;

        if (context == null || component == null) {
            throw new NullPointerException(Util.getExceptionMessageString(
                Util.NULL_PARAMETERS_ERROR_MESSAGE_ID));
        }

        if (log.isTraceEnabled()) {
            log.trace("Begin encoding component " + component.getId());
        } 
        
        // suppress rendering if "rendered" property on the component is
        // false.
        if (!component.isRendered()) {
            if (log.isTraceEnabled()) {
                log.trace("End encoding component " + component.getId() +
                          " since " +
                          "rendered attribute is set to false ");
            }
            return;
        }

        writer = context.getResponseWriter();
        assert (writer != null);

        currentValue = getCurrentValue(context, component);
        if (log.isTraceEnabled()) {
            log.trace("Value to be rendered " + currentValue);
        }
        getEndTextToRender(context, component, currentValue);
    }

    protected String getCurrentValue(FacesContext context, UIComponent component) {

        if (component instanceof UIInput) {
            Object submittedValue = ((UIInput) component).getSubmittedValue();
            if (submittedValue != null) {
                return (String) submittedValue;
            }
        }

        String currentValue = null;
        Object currentObj = getValue(component);
        if (currentObj != null) {
            currentValue = getFormattedValue(context, component, currentObj);
        }
        return currentValue;
    }

    protected void getEndTextToRender(FacesContext context,
                                      UIComponent component, String currentValue)
        throws IOException {

        ResponseWriter writer = context.getResponseWriter();
        assert (writer != null);
        boolean
            shouldWriteIdAttribute = false,
            isOutput = false;

        String
            style = (String) component.getAttributes().get("style"),
            styleClass = (String) component.getAttributes().get("styleClass");
        if (component instanceof UIInput) {
            writer.startElement("input", component);
            writeIdAttributeIfNecessary(context, writer, component);
            writer.writeAttribute("type", "text", null);
            writer.writeAttribute("name", (component.getClientId(context)),
                                  "clientId");

            // render default text specified
            if (currentValue != null) {
                writer.writeAttribute("value", currentValue, "value");
            }
            if (null != styleClass) {
                writer.writeAttribute("class", styleClass, "styleClass");
            }

            // style is rendered as a passthur attribute
            Util.renderPassThruAttributes(context, writer, component);
            Util.renderBooleanPassThruAttributes(writer, component);

            writer.endElement("input");

        } else if (isOutput = (component instanceof UIOutput)) {
            if (null != styleClass || null != style ||
                Util.hasPassThruAttributes(component) ||
                (shouldWriteIdAttribute = shouldWriteIdAttribute(component))) {
                writer.startElement("span", component);
                writeIdAttributeIfNecessary(context, writer, component);
                if (null != styleClass) {
                    writer.writeAttribute("class", styleClass, "styleClass");
                }
                // style is rendered as a passthru attribute
                Util.renderPassThruAttributes(context, writer, component);
                Util.renderBooleanPassThruAttributes(writer, component);

            }
            if (currentValue != null) {
                Object val = null;
                boolean escape = true;
                if (null != (val = component.getAttributes().get("escape"))) {
                    if (val instanceof Boolean) {
                        escape = ((Boolean) val).booleanValue();
                    } else if (val instanceof String) {
                        try {
                            escape =
                                Boolean.valueOf((String) val).booleanValue();
                        } catch (Throwable e) {
                        }
                    }
                }
                if (escape) {
                    writer.writeText(currentValue, "value");
                } else {
                    writer.write(currentValue);
                }
                writer.writeText(" FROM THE CUSTOM RENDERER", null);
            }
        }
        if (isOutput && (null != styleClass || null != style ||
            Util.hasPassThruAttributes(component) ||
            shouldWriteIdAttribute)) {
            writer.endElement("span");
        }
    }

    protected Object getValue(UIComponent component) {
        if (component instanceof ValueHolder) {
            Object value = ((ValueHolder) component).getValue();
            if (log.isDebugEnabled()) {
                log.debug("component.getValue() returned " + value);
            }
            return value;
        }

        return null;
    }

    protected String getFormattedValue(FacesContext context, UIComponent component,
                                       Object currentValue)
        throws ConverterException {

        String result = null;
        // formatting is supported only for components that support
        // converting value attributes.
        if (!(component instanceof ValueHolder)) {
            if (currentValue != null) {
                result = currentValue.toString();
            }
            return result;
        }

        Converter converter = null;

        // If there is a converter attribute, use it to to ask application
        // instance for a converter with this identifer.

        if (component instanceof ValueHolder) {
            converter = ((ValueHolder) component).getConverter();
        }

        // if value is null and no converter attribute is specified, then
        // return a zero length String.
        if (converter == null && currentValue == null) {
            return "";
        }

        if (converter == null) {
            // Do not look for "by-type" converters for Strings
            if (currentValue instanceof String) {
                return (String) currentValue;
            }

            // if converter attribute set, try to acquire a converter
            // using its class type.

            Class converterType = currentValue.getClass();
            converter = Util.getConverterForClass(converterType, context);

            // if there is no default converter available for this identifier,
            // assume the model type to be String.
            if (converter == null && currentValue != null) {
                result = currentValue.toString();
                return result;
            }
        }

        if (converter != null) {
            result = converter.getAsString(context, component, currentValue);

            return result;
        } else {
            // throw converter exception if no converter can be
            // identified
	    Object [] params = {
		currentValue,
		"null Converter"
	    };
	    
            throw new ConverterException(MessageFactory.getMessage(
                context, Util.CONVERSION_ERROR_MESSAGE_ID, params));
        }
    }
    private boolean shouldWriteIdAttribute(UIComponent component) {
        String id;
        return (null != (id = component.getId()) &&
            !id.startsWith(UIViewRoot.UNIQUE_ID_PREFIX));
    }
                                                                                                                   
    private void writeIdAttributeIfNecessary(FacesContext context,
                                               ResponseWriter writer,
                                               UIComponent component) {
        String id;
        if (shouldWriteIdAttribute(component)) {
            try {
                writer.writeAttribute("id", component.getClientId(context),
                                      "id");
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    // PENDING I18N
                    log.debug("Can't write ID attribute" + e.getMessage());
                }
            }
        }
    }


    // The testcase for this class is TestRenderers_2.java

} // end of class TextRenderer


