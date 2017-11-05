/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8156514
 * @library /lib/testlibrary server
 * @build jdk.testlibrary.SimpleSSLContext
 * @modules java.base/sun.net.www.http
 *          jdk.incubator.httpclient/jdk.incubator.http.internal.common
 *          jdk.incubator.httpclient/jdk.incubator.http.internal.frame
 *          jdk.incubator.httpclient/jdk.incubator.http.internal.hpack
 * @run testng/othervm -Djdk.httpclient.HttpClient.log=frames,ssl,requests,responses,errors RedirectTest
 */

import java.net.*;
import jdk.incubator.http.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.Arrays;
import java.util.Iterator;
import org.testng.annotations.Test;
import static jdk.incubator.http.HttpClient.Version.HTTP_2;
import static jdk.incubator.http.HttpRequest.BodyPublisher.fromString;
import static jdk.incubator.http.HttpResponse.BodyHandler.asString;

public class RedirectTest {
    static int httpPort, altPort;
    static Http2TestServer httpServer, altServer;
    static HttpClient client;

    static String httpURIString, altURIString1, altURIString2;

    static Supplier<String> sup(String... args) {
        Iterator<String> i = Arrays.asList(args).iterator();
        // need to know when to stop calling it.
        return () -> i.next();
    }

    static void initialize() throws Exception {
        try {
            client = getClient();
            httpServer = new Http2TestServer(false, 0, null, null);

            httpPort = httpServer.getAddress().getPort();
            altServer = new Http2TestServer(false, 0, null, null);
            altPort = altServer.getAddress().getPort();

            // urls are accessed in sequence below. The first two are on
            // different servers. Third on same server as second. So, the
            // client should use the same http connection.
            httpURIString = "http://127.0.0.1:" + httpPort + "/foo/";
            altURIString1 = "http://127.0.0.1:" + altPort + "/redir";
            altURIString2 = "http://127.0.0.1:" + altPort + "/redir/again";

            httpServer.addHandler(new Http2RedirectHandler(sup(altURIString1)), "/foo");
            altServer.addHandler(new Http2RedirectHandler(sup(altURIString2)), "/redir");
            altServer.addHandler(new Http2EchoHandler(), "/redir/again");

            httpServer.start();
            altServer.start();
        } catch (Throwable e) {
            System.err.println("Throwing now");
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    public static void test() throws Exception {
        try {
            initialize();
            simpleTest();
        } catch (Throwable tt) {
            System.err.println("tt caught");
            tt.printStackTrace();
        } finally {
            httpServer.stop();
            altServer.stop();
        }
    }

    static HttpClient getClient() {
        if (client == null) {
            client = HttpClient.newBuilder()
                               .followRedirects(HttpClient.Redirect.ALWAYS)
                               .version(HTTP_2)
                               .build();
        }
        return client;
    }

    static URI getURI() {
        return URI.create(httpURIString);
    }

    static void checkStatus(int expected, int found) throws Exception {
        if (expected != found) {
            System.err.printf ("Test failed: wrong status code %d/%d\n",
                expected, found);
            throw new RuntimeException("Test failed");
        }
    }

    static void checkStrings(String expected, String found) throws Exception {
        if (!expected.equals(found)) {
            System.err.printf ("Test failed: wrong string %s/%s\n",
                expected, found);
            throw new RuntimeException("Test failed");
        }
    }

    static final String SIMPLE_STRING = "Hello world Goodbye world";

    static void simpleTest() throws Exception {
        URI uri = getURI();
        System.err.println("Request to " + uri);

        HttpClient client = getClient();
        HttpRequest req = HttpRequest.newBuilder(uri)
                                     .POST(fromString(SIMPLE_STRING))
                                     .build();
        CompletableFuture<HttpResponse<String>> cf = client.sendAsync(req, asString());
        HttpResponse<String> response = cf.join();

        checkStatus(200, response.statusCode());
        String responseBody = response.body();
        checkStrings(SIMPLE_STRING, responseBody);

        System.err.println("DONE");
        Thread.sleep (6000);
    }
}
