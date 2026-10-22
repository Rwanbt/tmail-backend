/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR       *
 *  PURPOSE. See the Affero GNU General Public License for more      *
 *  details.                                                         *
 ********************************************************************/

package com.linagora.tmail.james.jmap.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration proof of the production SSRF address policy.
 *
 * <p>The policy itself lives in the pinned Apache James submodule. This suite
 * exercises it through the production TMail entry point
 * {@link SsrfRemoteUrlValidator}, with the real system resolver, so the
 * addresses are resolved exactly like a JMAP upload-from-URL request would
 * resolve them. It covers the ranges the qualification requires: private
 * IPv4, private IPv6, the IPv6 cloud metadata endpoint, IPv4-mapped IPv6 and
 * the alternative IPv4 notations (decimal, octal, hexadecimal) that the JDK
 * resolver silently translates to a loopback address.
 *
 * <p>The bound address is never contacted: the resolution is rejected before
 * any connection is established.
 */
public class SsrfRemoteUrlValidatorTest {
    private final SsrfRemoteUrlValidator validator = new SsrfRemoteUrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {
        // IPv4 loopback and private ranges
        "http://127.0.0.1/",
        "http://10.0.0.1/",
        "http://172.16.0.1/",
        "http://192.168.0.1/",
        // IPv4 link local: the cloud metadata endpoint
        "http://169.254.169.254/",
        // Wildcard and shared address space
        "http://0.0.0.0/",
        "http://100.64.0.1/",
        // IPv6 loopback, unique local and link local
        "http://[::1]/",
        "http://[fc00::1]/",
        "http://[fe80::1]/",
        // IPv4-mapped IPv6: the traffic ends up on the embedded IPv4 address
        "http://[::ffff:127.0.0.1]/",
        // Alternative IPv4 notations resolved by the JDK to 127.0.0.1
        "http://2130706433/",
        "http://0177.0.0.1/",
        "http://0x7f000001/"
    })
    public void shouldRejectEveryAddressReachingTheLocalNetwork(String url) {
        assertThatThrownBy(() -> validator.validate(URI.create(url)).block())
            .as("the production SSRF policy must refuse %s", url)
            .isNotNull();
    }

    @Test
    public void shouldAcceptAPublicAddress() {
        assertThatCode(() -> validator.validate(URI.create("http://93.184.216.34/")).block())
            .as("a public address must not be refused by the SSRF policy")
            .doesNotThrowAnyException();
    }
}
