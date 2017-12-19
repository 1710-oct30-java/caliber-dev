package com.revature.caliber.controllers;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.revature.caliber.beans.Trainer;
import com.revature.caliber.beans.TrainerRole;
import com.revature.caliber.exceptions.NotAuthorizedException;
import com.revature.caliber.security.impl.AbstractSalesforceSecurityHelper;
import com.revature.caliber.security.models.SalesforceUser;
/**
 * The type Boot controller.
 */
@Controller
@PropertySource("classpath:application.properties")
public class BootController extends AbstractSalesforceSecurityHelper {
    private static final Logger log = Logger.getLogger(BootController.class);
    private static final String INDEX = "index";
    @Value("${caliber.dev.mode}")
    private boolean debug;
    private static final String DEBUG_USER_LOGIN = "patrick.walsh@revature.com";
    
    /**
     * Instantiates a new Boot controller.
     */
    public BootController() {
        super();
    }
    /**
     * Gathers Salesforce user data, authorizes user to access Caliber. Forwards to
     * the landing page according to the user's role.
     */
    @RequestMapping(value = "/caliber")
    public String devHomePage(HttpServletRequest servletRequest, HttpServletResponse servletResponse){
        log.info("Returning index");
        try{
	        if(debug){
	            // fake Salesforce User
	            SalesforceUser salesforceUser = new SalesforceUser();
	            salesforceUser.setEmail(DEBUG_USER_LOGIN);
	            String email = salesforceUser.getEmail();
	
	            // Http request to the training module to get the caliber user
	            String jsonString = getCaliberTrainer(servletRequest, email);
	            // authorize user
	            authorize(jsonString, salesforceUser, servletResponse);
	        }
        }catch(Exception e){
        	log.error(e);
        }
        return INDEX;
    }
    
    /**
     * Gets Caliber user from database (TRAINER table) and validates if provided
     * email is authorized to user Caliber. All authorized Caliber users must exist
     * as a TRAINER record with email matching that of Salesforce user email.
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
    /**
     * Parses a Json String containing TRAINER bean. Authorize the user with Caliber
     * and store their PreAuthenticatedAuthenticationToken in session. Adds
     * convenience 'role' cookie for AngularJS consumption.
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
}
