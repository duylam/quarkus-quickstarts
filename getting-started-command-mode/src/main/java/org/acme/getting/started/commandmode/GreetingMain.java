package org.acme.getting.started.commandmode;

import com.github.markusbernhardt.proxy.selector.misc.BufferedProxySelector;
import jakarta.inject.Inject;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import com.github.markusbernhardt.proxy.ProxySearch;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.KerberosCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.DigestSchemeFactory;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.auth.SPNegoSchemeFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.jboss.logging.Logger;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@QuarkusMain
public class GreetingMain implements QuarkusApplication {
    private final Logger logger = Logger.getLogger(GreetingMain.class);

    @Inject
    GreetingService service;

    @Override
    public int run(String... args) throws URISyntaxException {
        logger.info("Version 3");

        String useSubjectCredsOnlyPropKey = "javax.security.auth.useSubjectCredsOnly";
        String loginConfigPropKey = "java.security.auth.login.config";
        String krb5Debug = "sun.security.krb5.debug";
        String jgssDebug = "sun.security.jgss.debug";

        if (Objects.isNull(System.getProperty(useSubjectCredsOnlyPropKey))) {
            logger.info("System property %s is not set, set it ".formatted(useSubjectCredsOnlyPropKey));
            System.setProperty(useSubjectCredsOnlyPropKey, "false");
        }

        if (Objects.isNull(System.getProperty(krb5Debug))) {
            logger.info("System property %s is not set, set it ".formatted(krb5Debug));
            System.setProperty(krb5Debug, "true");
        }

        if (Objects.isNull(System.getProperty(jgssDebug))) {
            logger.info("System property %s is not set, set it ".formatted(jgssDebug));
            System.setProperty(jgssDebug, "true");
        }

        if (Objects.isNull(System.getProperty(loginConfigPropKey))) {
            Path path = Paths.get(Thread.currentThread().getContextClassLoader().getResource("jaas.conf").toURI());
            String path2 =  path.toAbsolutePath().toString();
            logger.info("System property %s is not set, set it to %s".formatted(loginConfigPropKey, path2));
            System.setProperty(loginConfigPropKey, path2);
        }

        //
        // Verbose log request and response
        //
        var builder = HttpClients.custom()
                .addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
                    logger.info("Request:");
                    logger.info(request.getRequestLine());
                    Arrays.stream(request.getAllHeaders()).forEach(logger::info);
                })
                .addInterceptorFirst((HttpResponseInterceptor) (response, context) -> {
                    logger.info("Response:");
                    logger.info(response.getStatusLine());
                    Arrays.stream(response.getAllHeaders()).forEach(logger::info);
                });

        //
        // Configure proxy url
        //
        ProxySelector proxySelector = ProxySearch.getDefaultProxySearch().getProxySelector();
        if (Objects.isNull(proxySelector)) {
            logger.info("proxy-vole found no proxy setting, fallback to ProxySelector.getDefault()");
            proxySelector = ProxySelector.getDefault();
        }

        logger.info("Got proxySelector type: %s".formatted(proxySelector.getClass().getName()));
        String url = "https://postman-echo.com/time/now";
        if (args.length>0) {
            url = args[0];
        }

        try {
            logger.info("Inspect output of proxy selector");
            proxySelector.select(new URI(url)).iterator().forEachRemaining(p -> {
                logger.info("Resolved proxy address: %s%n".formatted(p.address()));
            });
        } catch (URISyntaxException e) {
            logger.error("failed to get proxy setting", e);
        }

        builder.setRoutePlanner(new SystemDefaultRoutePlanner(proxySelector));
        //builder.setProxy(new HttpHost("proxyserver", 3128));

        //
        // Configure proxy authentication
        //
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                AuthScope.ANY,
                // For Kerberos/Negotiate (SPNEGO), typically no username/password is used
                // because the credentials come from the ticket cache or keytab.
                new KerberosCredentials(null)
        );
//        credsProvider.setCredentials(
//                AuthScope.ANY,
                    // For Basic/Digest authentication, use username/password
//                new UsernamePasswordCredentials("proxyuser1", "123456")
//        );
        // Register the SPNEGO (Negotiate) and Digest auth schemes
        Registry authSchemeRegistry = RegistryBuilder.create()
                .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory(true))
                .register(AuthSchemes.DIGEST, new DigestSchemeFactory())
                .register(AuthSchemes.BASIC, new BasicSchemeFactory())
                .build();
        builder.setDefaultCredentialsProvider(credsProvider).setDefaultAuthSchemeRegistry(authSchemeRegistry);

        //
        // Send HTTP request
        //
        try {
            var response = builder.build().execute(new HttpGet(url));
            //logger.info("Response body: " + new String(response.getEntity().getContent().readAllBytes()));
        } catch (IOException e) {
            logger.error("Sending HTTP fails", e);
        }

        return 0;
    }
}
