/*
 * Copyright (c) 2012, Mayocat <hello@mayocat.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mayocat.localization.rest.resources;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import org.mayocat.configuration.general.GeneralSettings;
import org.mayocat.context.WebContext;
import org.mayocat.rest.Resource;
import org.mayocat.rest.annotation.ExistingTenant;
import org.mayocat.theme.Theme;
import org.xwiki.component.annotation.Component;

import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.yammer.dropwizard.assets.ResourceURL;

import asia.redact.bracket.properties.Properties;

/**
 * Expose theme localization properties.
 *
 * @version $Id$
 */
@Component("/api/localization/theme/")
@Path("/api/localization/theme/")
@Produces(MediaType.APPLICATION_JSON)
@ExistingTenant
public class LocalizationResource implements Resource
{
    public static final String LOCALIZATION_DIRECTORY = "localization";

    public static final String PROPERTIES_FILE_EXTENSION = ".properties";

    @Inject
    private WebContext context;

    @GET
    public Response listLocales()
    {
        List<Locale> locales;
        Map<String, String> endpoints = Maps.newHashMap();
        GeneralSettings settings = context.getSettings(GeneralSettings.class);

        locales = settings.getLocales().getOtherLocales().getValue();
        locales.add(settings.getLocales().getMainLocale().getValue());

        for (Locale locale : locales) {
            endpoints.put(locale.toLanguageTag(), "/api/localization/theme/" + locale.toLanguageTag());
        }

        return Response.ok(endpoints).build();
    }

    @GET
    @Path("{locale}")
    public Response getMessages(@PathParam("locale") Locale locale, @Context Request request)
    {

        Theme theme = context.getTheme();

        java.nio.file.Path propertiesPath = theme.getPath()
                .resolve(LOCALIZATION_DIRECTORY)
                .resolve(locale.toLanguageTag() + PROPERTIES_FILE_EXTENSION);

        File file = null;

        try {
            switch (theme.getType()) {
                case FILE_SYSTEM:
                    file = propertiesPath.toFile();
                    break;
                case CLASSPATH:
                    URI uri = Resources.getResource(propertiesPath.toString()).toURI();

                    if (uri.getScheme().equals("jar")) {
                        // Not supported for now
                        return Response.status(Response.Status.NOT_FOUND).build();
                    }

                    file = new File(uri);
                    break;
            }

            String tag = Files.hash(file, Hashing.murmur3_128()).toString();
            EntityTag eTag = new EntityTag(tag);

            URL url = file.toURI().toURL();
            long lastModified = ResourceURL.getLastModified(url);
            if (lastModified < 1) {
                // Something went wrong trying to get the last modified time: just use the current time
                lastModified = System.currentTimeMillis();
            }
            // zero out the millis since the date we get back from If-Modified-Since will not have them
            lastModified = (lastModified / 1000) * 1000;

            CacheControl cacheControl = new CacheControl();
            cacheControl.setMaxAge(24 * 3600);
            Response.ResponseBuilder builder = request.evaluatePreconditions(new Date(lastModified), eTag);

            if (builder == null) {
                Properties properties = Properties.Factory.getInstance(file, Charsets.UTF_8);
                builder = Response.ok(properties.getPropertyMap());
            }

            return builder.cacheControl(cacheControl).lastModified(new Date(lastModified)).build();
        } catch (URISyntaxException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        } catch (FileNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }
}
