/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.authentication;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.vault.VaultException;
import org.springframework.vault.authentication.AwsEc2AuthenticationOptions.Nonce;
import org.springframework.vault.client.VaultClients;
import org.springframework.vault.client.VaultClients.PrefixAwareUriTemplateHandler;
import org.springframework.vault.support.VaultToken;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link AwsEc2Authentication}.
 *
 * @author Mark Paluch
 */
public class AwsEc2AuthenticationUnitTests {

	private RestTemplate restTemplate;
	private MockRestServiceServer mockRest;

	@Before
	public void before() {

		RestTemplate restTemplate = VaultClients.createRestTemplate();
		restTemplate.setUriTemplateHandler(new PrefixAwareUriTemplateHandler());

		this.mockRest = MockRestServiceServer.createServer(restTemplate);
		this.restTemplate = restTemplate;
	}

	@Test
	public void shouldObtainIdentityDocument() {

		mockRest.expect(
				requestTo("http://169.254.169.254/latest/dynamic/instance-identity/pkcs7")) //
				.andExpect(method(HttpMethod.GET)) //
				.andRespond(withSuccess().body("Hello, world"));

		AwsEc2Authentication authentication = new AwsEc2Authentication(restTemplate);

		assertThat(authentication.getEc2Login()).containsEntry("pkcs7", "Hello, world")
				.containsKey("nonce").hasSize(2);
	}

	@Test
	public void shouldContainRole() {

		AwsEc2AuthenticationOptions options = AwsEc2AuthenticationOptions.builder()
				.role("ami").build();

		mockRest.expect(
				requestTo("http://169.254.169.254/latest/dynamic/instance-identity/pkcs7")) //
				.andExpect(method(HttpMethod.GET)) //
				.andRespond(withSuccess().body("Hello, world"));

		AwsEc2Authentication authentication = new AwsEc2Authentication(options,
				restTemplate, restTemplate);

		assertThat(authentication.getEc2Login()) //
				.containsEntry("pkcs7", "Hello, world") //
				.containsEntry("role", "ami") //
				.containsKey("nonce").hasSize(3);
	}

	@Test
	public void shouldLogin() {

		Nonce nonce = Nonce.provided("foo".toCharArray());

		AwsEc2AuthenticationOptions authenticationOptions = AwsEc2AuthenticationOptions
				.builder().nonce(nonce).build();

		mockRest.expect(
				requestTo("http://169.254.169.254/latest/dynamic/instance-identity/pkcs7")) //
				.andExpect(method(HttpMethod.GET)) //
				.andRespond(withSuccess().body("value"));

		mockRest.expect(requestTo("/auth/aws-ec2/login"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.pkcs7").value("value"))
				.andExpect(jsonPath("$.nonce").value("foo"))
				.andRespond(
						withSuccess()
								.contentType(MediaType.APPLICATION_JSON)
								.body("{"
										+ "\"auth\":{\"client_token\":\"my-token\", \"lease_duration\":20}"
										+ "}"));

		AwsEc2Authentication authentication = new AwsEc2Authentication(
				authenticationOptions, restTemplate, restTemplate);

		VaultToken login = authentication.login();

		assertThat(login).isInstanceOf(LoginToken.class);
		assertThat(login.getToken()).isEqualTo("my-token");
		assertThat(((LoginToken) login).getLeaseDuration()).isEqualTo(
				Duration.ofSeconds(20));
		assertThat(((LoginToken) login).isRenewable()).isFalse();
	}

	@Test
	public void authenticationChainShouldLogin() {

		Nonce nonce = Nonce.provided("foo".toCharArray());

		AwsEc2AuthenticationOptions options = AwsEc2AuthenticationOptions.builder()
				.nonce(nonce).build();

		mockRest.expect(
				requestTo("http://169.254.169.254/latest/dynamic/instance-identity/pkcs7")) //
				.andExpect(method(HttpMethod.GET)) //
				.andRespond(withSuccess().body("value"));

		mockRest.expect(requestTo("/auth/aws-ec2/login"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.pkcs7").value("value"))
				.andExpect(jsonPath("$.nonce").value("foo"))
				.andRespond(
						withSuccess()
								.contentType(MediaType.APPLICATION_JSON)
								.body("{"
										+ "\"auth\":{\"client_token\":\"my-token\", \"lease_duration\":20}"
										+ "}"));

		AuthenticationStepsExecutor executor = new AuthenticationStepsExecutor(
				AwsEc2Authentication.createAuthenticationSteps(options), restTemplate);
		VaultToken login = executor.login();

		assertThat(login).isInstanceOf(LoginToken.class);
		assertThat(login.getToken()).isEqualTo("my-token");
		assertThat(((LoginToken) login).getLeaseDuration()).isEqualTo(
				Duration.ofSeconds(20));
		assertThat(((LoginToken) login).isRenewable()).isFalse();
	}

	@Test(expected = VaultException.class)
	public void loginShouldFailWhileObtainingIdentityDocument() {

		mockRest.expect(
				requestTo("http://169.254.169.254/latest/dynamic/instance-identity/pkcs7")) //
				.andRespond(withServerError());

		new AwsEc2Authentication(restTemplate).login();
	}

	@Test(expected = VaultException.class)
	public void loginShouldFail() {

		mockRest.expect(requestTo("/auth/aws-ec2/login")) //
				.andRespond(withServerError());

		new AwsEc2Authentication(restTemplate) {
			@Override
			protected Map<String, String> getEc2Login() {
				return Collections.singletonMap("pkcs7", "value");
			}
		}.login();
	}
}
