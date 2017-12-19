package com.revature.caliber.data;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.revature.caliber.beans.Batch;
import com.revature.caliber.beans.Trainee;
import com.revature.caliber.exceptions.ServiceNotAvailableException;
import com.revature.caliber.salesforce.SalesforceTransformerToCaliber;
import com.revature.caliber.security.models.SalesforceUser;
import com.revature.salesforce.beans.SalesforceBatch;
import com.revature.salesforce.beans.SalesforceBatchResponse;
import com.revature.salesforce.beans.SalesforceTrainee;
import com.revature.salesforce.beans.SalesforceTraineeResponse;

/**
 * Interacts with Salesforce REST API and transforms data into Caliber beans
 * 
 * @author Patrick Walsh
 * @author
 * @author
 *
 */
@Repository
@PropertySource("classpath:application.properties")
public class SalesforceDAO {

	private static final Logger log = Logger.getLogger(SalesforceDAO.class);

	@Autowired
	private SalesforceTransformerToCaliber transformer;

	@Value("${caliber.dev.mode}")
	private boolean debug;

	@Value("${salesforce.instance.url}")
	private String salesforceInstanceUrl;
	@Value("${salesforce.api.url}")
	private String salesforceApiUrl;

	//////////// SOQL - Salesforce Object Query Language //////////////

	/**
	 * Will change as of version 2.0 Salesforce API in August/September 2017
	 * timeframe Used to populate the dropdown list of importable batches.
	 */
	@Value("select id, name, batch_start_date__c, batch_end_date__c, "
			+ "batch_trainer__r.name, batch_trainer__r.email, Co_Trainer__r.name, Co_Trainer__r.email, "
			+ "Skill_Type__c, Location__c, Type__c from training__c "
			+ "where batch_trainer__r.name != null and batch_start_date__c >= THIS_YEAR")
	private String relevantBatches;

	/**
	 * Will change as of version 2.0 Salesforce API in August/September 2017
	 * timeframe Once user selects a batch to import, use this to load all the
	 * Trainee details. ResourceId *MUST* be surrounded in single quotes to
	 * function properly
	 */
	@Value("select id, name, training_status__c, phone, email, MobilePhone, Training_Batch__c ,"
			+ " Training_Batch__r.name, Training_Batch__r.batch_start_date__c, Training_Batch__r.batch_end_date__c,"
			+ " Training_Batch__r.batch_trainer__r.name, rnm__Recruiter__r.name, account.name, "
			+ "Training_Batch__r.Co_Trainer__r.name, eintern_current_project_completion_pct__c , "
			+ "Training_Batch__r.Skill_Type__c, Training_Batch__r.Type__c, Screener__c, Screen_Feedback__c, "
			+ "Associates_Degree__c, Bachelors_Degree__c, Masters_Degree__c from Contact where training_batch__c =")
	private String batchDetails;

	//////////// REST Consumer Methods -- Salesforce REST API //////////////

	/**
	 * Get all the batches in the current year and future years. Access data
	 * using the Salesforce REST API
	 * 
	 * @return
	 */
	public List<Batch> getAllRelevantBatches() {
		List<Batch> relevantBatchesList = new LinkedList<>();

		try {
			SalesforceBatchResponse response = new ObjectMapper().readValue(
					getFromSalesforce(relevantBatches).getEntity().getContent(), SalesforceBatchResponse.class);
			log.info("Found " + response.getTotalSize() + " batches: " + response);
			transformer = new SalesforceTransformerToCaliber();

			for (SalesforceBatch salesForceBatch : response.getRecords()) {
				relevantBatchesList.add(transformer.transformBatch(salesForceBatch));
			}
		} catch (IOException e) {
			log.error("Cannot get Salesforce batches:  " + e);
			// log the Salesforce error JSON
			ObjectMapper mapper = new ObjectMapper();
			try {
				log.error(
						mapper.readValue(getFromSalesforce(relevantBatches).getEntity().getContent(), JsonNode.class));
			} catch (IOException e1) {
				log.error("Cannot get Salesforce error message:  " + e1);
			}
			throw new ServiceNotAvailableException();
		}

		return relevantBatchesList;
	}

	/**
	 * Get all the trainees for a single batch. Access data using the Salesforce
	 * REST API
	 * 
	 * @return
	 */
	public List<Trainee> getBatchDetails(String resourceId) {
		String query = batchDetails + "'" + resourceId + "'";
		List<Trainee> trainees = new LinkedList<>();

		try {
			SalesforceTraineeResponse response = new ObjectMapper()
					.readValue(getFromSalesforce(query).getEntity().getContent(), SalesforceTraineeResponse.class);
			log.info(response);

			transformer = new SalesforceTransformerToCaliber();
			for (SalesforceTrainee trainee : response.getRecords()) {
				trainees.add(transformer.transformTrainee(trainee));
			}

		} catch (IOException e) {
			log.error("Cannot get batch details from Salesforce: cause " + e);
			// log the Salesforce error JSON
			ObjectMapper mapper = new ObjectMapper();
			try {
				log.error(mapper.readValue(getFromSalesforce(query).getEntity().getContent(), JsonNode.class));
			} catch (IOException e1) {
				log.error("Cannot get Salesforce error message:  " + e1);
			}
			throw new ServiceNotAvailableException();
		}
		return trainees;
	}

	//////////// API Helper Methods //////////////

	/**
	 * Helper method to call HTTP GET request to Salesforce REST API
	 * 
	 * @param soql
	 * @return
	 */
	private HttpResponse getFromSalesforce(String soql) {
		try {
			HttpClient httpClient = HttpClientBuilder.create().build();
			String url = new URIBuilder(salesforceInstanceUrl).setScheme("https").setHost(salesforceInstanceUrl)
					.setPath(salesforceApiUrl).setParameter("q", soql).build().toString();
			HttpGet getRequest = new HttpGet(url);
			getRequest.setHeader("Authorization", "Bearer " + getAccessToken());
			return httpClient.execute(getRequest);
		} catch (IOException | URISyntaxException e) {
			log.error("Unable to fetch Salesforce data: cause " + e);
			throw new ServiceNotAvailableException();
		}
	}

	/**
	 * Helper method to return the Salesforce access_token being managed by
	 * Spring Security
	 * 
	 * @return
	 */
	private String getAccessToken() {
		if (debug)
			return "00D0n0000000Q1l!AQQAQO6EIphsBcQV0Jgk.XTNY_xmcIEdqYGDhhDznW83aYM0Gsy2MbiwswbL0kFXAa6lX.v8d097daDSlYYZeahW4GitXFCv";
		else
			return ((SalesforceUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal())
					.getSalesforceToken().getAccessToken();
	}

	/**
	 * Get all the batches in the current year and future years. Access data
	 * using the Salesforce REST API. Returns as String in case the result is
	 * not actually a batch. Used to debug environment issues.
	 * 
	 * @return
	 */
	public String getSalesforceResponseString() {
		try {
			return new ObjectMapper()
					.readValue(getFromSalesforce(relevantBatches).getEntity().getContent(), JsonNode.class).asText();
		} catch (IOException e) {
			log.error("Cannot get Salesforce datas:  " + e);
			return null;
		}
	}

	/**
	 * Get batch details. Access data using the Salesforce REST API. Returns as
	 * String in case the result is not actually a batch. Used to debug
	 * environment issues.
	 * 
	 * @return
	 */
	public String getSalesforceTraineeResponseString(String resourceId) {
		try {
			return new ObjectMapper()
					.readValue(getFromSalesforce(batchDetails + "'" + resourceId + "'").getEntity().getContent(),
							JsonNode.class)
					.asText();
		} catch (IOException e) {
			log.error("Cannot get Salesforce datas:  " + e);
			return null;
		}
	}

	public void setTransformer(SalesforceTransformerToCaliber transformer) {
		this.transformer = transformer;
	}

}
