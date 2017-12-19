package com.revature.caliber.security.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.revature.caliber.beans.Trainer;
import com.revature.caliber.beans.TrainerRole;
import com.revature.caliber.exceptions.NotAuthorizedException;
import com.revature.caliber.security.Authorization;
import com.revature.caliber.security.models.SalesforceToken;
import com.revature.caliber.security.models.SalesforceUser;

/**
 * Created by louislopez on 1/18/17.
 */
@Controller
@PropertySource("classpath:application.properties")
public class AuthorizationImpl extends AbstractSalesforceSecurityHelper implements Authorization {
	@Value("/caliber/")
	private String forwardUrl;
	private static final Logger log = Logger.getLogger(AuthorizationImpl.class);
	@Value("${caliber.dev.mode}")
	private boolean debug;
	private static final String DEBUG_USER_LOGIN = "patrick.walsh@revature.com";
	private static final String REDIRECT = "redirect:";
	private static final String REVATURE = "http://www.revature.com/";

	public AuthorizationImpl() {
		super();
	}

	/**
	 * Redirects the request to perform authentication.
	 * 
	 */
	@RequestMapping("/")
	public ModelAndView openAuthURI() {
		if (debug) {
			return new ModelAndView(REDIRECT + redirectUrl);
		}
		log.debug("redirecting to salesforce authorization");
		return new ModelAndView(REDIRECT + loginURL + authURL + "?response_type=code&client_id=" + clientId
				+ "&redirect_uri=" + redirectUri);
	}

	/**
	 * Retrieves Salesforce authentication token from Salesforce REST API
	 * 
	 * @param code
	 * @throws URISyntaxException
	 */
	@RequestMapping("/authenticated")
	public ModelAndView generateSalesforceToken(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			@RequestParam(value = "code") String code, RedirectAttributes redirectAttributes)
			throws IOException, URISyntaxException {

		log.debug("in authenticated method");
		// Posting code and additional parameters to oauth/token
		HttpClient httpClient = HttpClientBuilder.create().build();
		HttpPost post = new HttpPost(loginURL + accessTokenURL);
		List<NameValuePair> parameters = new ArrayList<>();
		parameters.add(new BasicNameValuePair("grant_type", "authorization_code"));
		parameters.add(new BasicNameValuePair("client_secret", clientSecret));
		parameters.add(new BasicNameValuePair("client_id", clientId));
		parameters.add(new BasicNameValuePair("redirect_uri", redirectUri));
		parameters.add(new BasicNameValuePair("code", code));
		post.setEntity(new UrlEncodedFormEntity(parameters));

		log.debug("Generating Salesforce token");
		// Getting token response from salesforce
		HttpResponse response = httpClient.execute(post);
		String salesTokenString = toJsonString(response.getEntity().getContent());
		
		try {
			tryAuthorize(servletRequest, servletResponse, salesTokenString);
		} catch (AuthenticationCredentialsNotFoundException e) {
			log.error("error thrown:", e);
			return new ModelAndView("redirect:/");
		}

		log.debug("Forwarding to : " + redirectUrl);
		return new ModelAndView(REDIRECT + redirectUrl);
	}

	@RequestMapping(value = "/authenticated_token")
	public void authenticateAPI(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
			throws IOException, URISyntaxException {
		log.info("API log in test");
		String salesTokenString = servletRequest.getParameter("salestoken");
		
		try {
			tryAuthorize(servletRequest, servletResponse, salesTokenString);
		} catch (AuthenticationCredentialsNotFoundException e) {
			log.error("error thrown:", e);
		}
	}

	/**
	 * Clears session information and logout the user.
	 * 
	 * Note: Still retrieving 302 on access-token and null refresh-token
	 * 
	 * @param auth
	 * @param session
	 * @return
	 * @throws IOException
	 * @throws ServletException
	 */
	@RequestMapping(value = "/revoke", method = RequestMethod.GET)
	public ModelAndView revoke(Authentication auth, HttpServletRequest servletRequest,
			HttpServletResponse servletResponse) throws IOException, ServletException {
		if (auth == null)
			return new ModelAndView(REDIRECT + REVATURE);
		if (!debug) {
			// revoke all tokens from the Salesforce
			String accessToken = ((SalesforceUser) auth.getPrincipal()).getSalesforceToken().getAccessToken();
			revokeToken(accessToken);
		}
		// logout and clear Spring Security Context
		servletRequest.logout();
		SecurityContextHolder.clearContext();
		log.info("User has logged out");
		return new ModelAndView(REDIRECT + REVATURE);
	}

	private void revokeToken(String token) throws ClientProtocolException, IOException {
		log.info("POST " + loginURL + revokeUrl);
		HttpClient httpClient = HttpClientBuilder.create().build();
		HttpPost post = new HttpPost(loginURL + revokeUrl);
		post.setHeader("Content-Type", "application/x-www-form-urlencoded");
		List<NameValuePair> parameters = new ArrayList<>();
		parameters.add(new BasicNameValuePair("token", token));
		post.setEntity(new UrlEncodedFormEntity(parameters));
		HttpResponse response = httpClient.execute(post);
		log.info("Revoke token : " + response.getStatusLine().getStatusCode() + " "
				+ response.getStatusLine().getReasonPhrase());
	}

	public void setAuthURL(String authURL) {
		this.authURL = authURL;
	}

	public void setAccessTokenURL(String accessTokenURL) {
		this.accessTokenURL = accessTokenURL;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	public void setRedirectUri(String redirectUri) {
		this.redirectUri = redirectUri;
	}

	/**
	 * Gets Caliber user from database (TRAINER table) and validates if provided
	 * email is authorized to user Caliber. All authorized Caliber users must
	 * exist as a TRAINER record with email matching that of Salesforce user
	 * email.
	 * 
	 * @param servletRequest
	 * @param email
	 * @return
	 * @throws URISyntaxException
	 * @throws IOException
	 */
	private String getCaliberTrainer(HttpServletRequest servletRequest, String email)
			throws URISyntaxException, IOException {
		HttpClient httpClient = HttpClientBuilder.create().build();
		URIBuilder uriBuilder = new URIBuilder();
		uriBuilder.setScheme(servletRequest.getScheme()).setHost(servletRequest.getServerName())
				.setPort(servletRequest.getServerPort()).setPath("/training/trainer/byemail/" + email + "/");
		URI uri = uriBuilder.build();
		HttpGet httpGet = new HttpGet(uri);
		HttpResponse response = httpClient.execute(httpGet);
		String jsonString = toJsonString(response.getEntity().getContent());
		// check if we actually got back JSON object from the Salesforce
		if (!jsonString.contains(email)) {
			log.fatal("Training API returned: " + jsonString);
			throw new NotAuthorizedException();
		}
		log.info(jsonString);
		return jsonString;
	}
	
	private void tryAuthorize(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String salesTokenString) throws URISyntaxException, IOException {
		SalesforceUser salesforceUser;
		
		if (debug) {
			// fake Salesforce User
			salesforceUser = new SalesforceUser();
			salesforceUser.setEmail(DEBUG_USER_LOGIN);

		} else {
			SalesforceToken salesforceToken = getSalesforceToken(salesTokenString);
			// Http request to the salesforce module to get the Salesforce
			// user
			salesforceUser = getSalesforceUserDetails(servletRequest, salesforceToken);
		}
		
		String email = salesforceUser.getEmail();
		// Http request to the training module to get the caliber user
		String jsonString = getCaliberTrainer(servletRequest, email);
		// authorize user
		authorize(jsonString, salesforceUser, servletResponse);
	}

	/**
	 * Parses a Json String containing TRAINER bean. Authorize the user with
	 * Caliber and store their PreAuthenticatedAuthenticationToken in session.
	 * Adds convenience 'role' cookie for AngularJS consumption.
	 * 
	 * @param jsonString
	 * @param salesforceUser
	 * @param servletResponse
	 * @throws IOException
	 */
	private void authorize(String jsonString, SalesforceUser salesforceUser, HttpServletResponse servletResponse)
			throws IOException {
		JSONObject jsonObject = new JSONObject(jsonString);
		if (jsonObject.getString("email").equals(salesforceUser.getEmail())) {
			log.info("Logged in user " + jsonObject.getString("email") + " now hasRole: "
					+ jsonObject.getString("tier"));
			salesforceUser.setRole(jsonObject.getString("tier"));
			salesforceUser.setCaliberUser(new ObjectMapper().readValue(jsonString, Trainer.class));
			// check if user is active
			if (salesforceUser.getCaliberUser().getTier().equals(TrainerRole.ROLE_INACTIVE))
				throw new NotAuthorizedException();
		} else {
			throw new NotAuthorizedException();
		}
		// store custom user Authentication obj in SecurityContext
		Authentication auth = new PreAuthenticatedAuthenticationToken(salesforceUser, salesforceUser.getUserId(),
				salesforceUser.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(auth);
		servletResponse.addCookie(new Cookie("role", jsonObject.getString("tier")));
	}

	/**
	 * Retrieve the salesforce access_token from the forwarded request
	 * 
	 * @param token
	 * @return
	 * @throws IOException
	 */
	private SalesforceToken getSalesforceToken(String token) throws IOException {
		log.debug("Checking for the salesforce token");
		if (token != null) {
			log.info("Parse salesforce token from forwarded request: " + token);
			try {
				ObjectMapper mapper = new ObjectMapper();
				mapper.configure(Feature.ALLOW_NUMERIC_LEADING_ZEROS, true);
				return mapper.readValue(token, SalesforceToken.class);
			} catch (Exception e) {
				log.error(e);
				// log the Salesforce error JSON
				ObjectMapper mapper = new ObjectMapper();
				log.error(mapper.readValue(token, JsonNode.class));
			}
		}
		log.debug("failed to parse token from forwarded request: ");
		throw new AuthenticationCredentialsNotFoundException("Salesforce token expired.");
	}
	/**
	 * Makes a request to Salesforce REST API to retrieve the authenticated
	 * user's details
	 * 
	 * @param servletRequest
	 * @param salesforceToken
	 * @return
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	private SalesforceUser getSalesforceUserDetails(HttpServletRequest servletRequest, SalesforceToken salesforceToken)
			throws IOException, URISyntaxException {
		HttpClient httpClient = HttpClientBuilder.create().build();
		URIBuilder uriBuilder = new URIBuilder();
		uriBuilder.setScheme(servletRequest.getScheme()).setHost(servletRequest.getServerName())
				.setPort(servletRequest.getServerPort()).setPath("/getSalesforceUser/")
				.setParameter("endpoint", salesforceToken.getId())
				.setParameter("accessToken", salesforceToken.getAccessToken());
		URI uri = uriBuilder.build();
		HttpGet httpGet = new HttpGet(uri);
		HttpResponse response = httpClient.execute(httpGet);
		String user = toJsonString(response.getEntity().getContent());
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(Feature.ALLOW_NUMERIC_LEADING_ZEROS, true);
		SalesforceUser salesforceUser = mapper.readValue(user, SalesforceUser.class);
		salesforceUser.setSalesforceToken(salesforceToken);
		return salesforceUser;
	}
}
