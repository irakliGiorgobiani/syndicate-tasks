package com.task10;

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.*;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.ItemUtils;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@LambdaHandler(
		lambdaName = "api_handler",
		roleName = "api_handler-role",
		isPublishVersion = false,
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
@DependsOn(name = "${tables_table}", resourceType = ResourceType.DYNAMODB_TABLE)
@DependsOn(name = "${reservations_table}", resourceType = ResourceType.DYNAMODB_TABLE)
@DependsOn(name = "${booking_userpool}", resourceType = ResourceType.COGNITO_USER_POOL)
@EnvironmentVariables(
		value = {
				@EnvironmentVariable(key = "region", value = "${region}"),
				@EnvironmentVariable(key = "tables_table", value = "${tables_table}"),
				@EnvironmentVariable(key = "reservations_table", value = "${reservations_table}"),
				@EnvironmentVariable(key = "booking_userpool", value = "${booking_userpool}")
		}
)
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private static final Log log = LogFactory.getLog(ApiHandler.class);
	private final AmazonDynamoDB dynamoDBClient = AmazonDynamoDBClientBuilder.defaultClient();
	private final DynamoDB dynamoDB = new DynamoDB(dynamoDBClient);
	private final AWSCognitoIdentityProvider cognitoClient = AWSCognitoIdentityProviderClientBuilder.defaultClient();

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		LambdaLogger logger = context.getLogger();
		Map<String, Object> response = new HashMap<>();

		try {
			String httpMethod = (String) event.get("httpMethod");
			String path = (String) event.get("path");

			if ("/signup".equals(path) && "POST".equals(httpMethod)) {
				return handleSignup(event, logger);
			} else if ("/signin".equals(path) && "POST".equals(httpMethod)) {
				return handleSignin(event, logger);
			} else {
				response.put("statusCode", 400);
				response.put("body", "Unsupported path or method");
			}
		} catch (Exception e) {
			logger.log("Error: " + e.getMessage());
			response.put("statusCode", 500);
			response.put("body", "Internal Server Error");
		}

		return response;
	}


	private Map<String, Object> handleSignup(Map<String, Object> event, LambdaLogger logger) {
		Map<String, Object> response = new HashMap<>();
		try {
			ObjectMapper objectMapper = new ObjectMapper();
			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);
			String email = (String) body.get("email");
			String password = (String) body.get("password");

			validateCredentials(email, password, logger);

			String userPoolId = retrieveUserPoolId(logger);
			ListUsersRequest listUsersRequest = new ListUsersRequest()
					.withUserPoolId(userPoolId)
					.withFilter("email = \"" + email + "\"");
			ListUsersResult listUsersResult = cognitoClient.listUsers(listUsersRequest);

			if (!listUsersResult.getUsers().isEmpty()) {
				response.put("statusCode", 400);
				response.put("body", "User already exists");
				return response;
			}

			AdminCreateUserRequest createUserRequest = new AdminCreateUserRequest()
					.withUserPoolId(userPoolId)
					.withUsername(email)
					.withUserAttributes(new AttributeType().withName("email").withValue(email))
					.withMessageAction(MessageActionType.SUPPRESS);

			cognitoClient.adminCreateUser(createUserRequest);

			AdminSetUserPasswordRequest setUserPasswordRequest = new AdminSetUserPasswordRequest()
					.withUserPoolId(userPoolId)
					.withUsername(email)
					.withPassword(password)
					.withPermanent(true);
			cognitoClient.adminSetUserPassword(setUserPasswordRequest);

			response.put("statusCode", 200);
			response.put("body", "User created successfully");
		} catch (Exception ex) {
			logger.log("Signup Error: " + ex.getMessage());
			response.put("statusCode", 400);
			response.put("body", ex.getMessage());
		}
		return response;
	}

	private Map<String, Object> handleSignin(Map<String, Object> event, LambdaLogger logger) {
		Map<String, Object> response = new LinkedHashMap<>();
		try {
			ObjectMapper objectMapper = new ObjectMapper();
			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);
			String email = (String) body.get("email");
			String password = (String) body.get("password");

			validateCredentials(email, password, logger);

			String userPoolId = retrieveUserPoolId(logger);
			String clientId = retrieveClientId(userPoolId, logger);

			AdminInitiateAuthRequest authRequest = new AdminInitiateAuthRequest()
					.withAuthFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
					.withUserPoolId(userPoolId)
					.withClientId(clientId)
					.withAuthParameters(Map.of("USERNAME", email, "PASSWORD", password));

			AdminInitiateAuthResult authResult = cognitoClient.adminInitiateAuth(authRequest);

			if (authResult.getAuthenticationResult() != null) {
				String accessToken = authResult.getAuthenticationResult().getIdToken();
				response.put("statusCode", 200);
				response.put("body", objectMapper.writeValueAsString(Map.of("accessToken", accessToken)));
			} else {
				throw new RuntimeException("Authentication failed.");
			}
		} catch (Exception ex) {
			logger.log("Signin Error: " + ex.getMessage());
			response.put("statusCode", 400);
			response.put("body", ex.getMessage());
		}
		return response;
	}

	private void validateCredentials(String email, String password, LambdaLogger logger) {
		if (!isValidEmail(email)) {
			logger.log("Invalid email: " + email);
			throw new IllegalArgumentException("Email is invalid");
		}

		if (!isValidPassword(password)) {
			logger.log("Invalid password: " + password);
			throw new IllegalArgumentException("Password is invalid");
		}
	}

	private boolean isValidEmail(String email) {
		Matcher matcher = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$").matcher(email);
		return matcher.matches();
	}

	private boolean isValidPassword(String password) {
		Matcher matcher = Pattern.compile("^(?=.*[0-9])(?=.*[a-zA-Z])(?=.*[\\$%\\^\\*]).{12,}$").matcher(password);
		return matcher.matches();
	}

	private String retrieveUserPoolId(LambdaLogger logger) {
		return getUserPoolIdByName(System.getenv("booking_userpool"))
				.orElseThrow(() -> new IllegalArgumentException("User pool not found"));
	}

	private String retrieveClientId(String userPoolId, LambdaLogger logger) {
		return getClientIdByUserPoolName(userPoolId)
				.orElseThrow(() -> new IllegalArgumentException("Client ID not found"));
	}

	private Optional<String> getUserPoolIdByName(String poolName) {
		ListUserPoolsResult poolsResult = cognitoClient.listUserPools(new ListUserPoolsRequest().withMaxResults(60));
		return poolsResult.getUserPools().stream()
				.filter(pool -> pool.getName().equals(poolName))
				.map(UserPoolDescriptionType::getId)
				.findFirst();
	}

	private Optional<String> getClientIdByUserPoolName(String userPoolId) {
		ListUserPoolClientsRequest listClientsRequest = new ListUserPoolClientsRequest().withUserPoolId(userPoolId);
		ListUserPoolClientsResult clientsResult = cognitoClient.listUserPoolClients(listClientsRequest);
		return clientsResult.getUserPoolClients().stream()
				.findFirst()
				.map(UserPoolClientDescription::getClientId);
	}

	private Map<String, Object> handleGetTables(LambdaLogger logger) {
		ScanRequest scanRequest = new ScanRequest().withTableName(System.getenv("tables_table"));
		ScanResult result = dynamoDBClient.scan(scanRequest);
		List<Map<String, Object>> items = new ArrayList<>();
		result.getItems().forEach(item -> items.add(ItemUtils.toSimpleMapValue(item)));
		return buildResponse(200, items);
	}

	private Map<String, Object> handleGetTableById(String tableId, LambdaLogger logger) {
		Map<String, Object> response = new HashMap<>();
		try {
			Map<String, AttributeValue> keyToGet = new HashMap<>();
			keyToGet.put("tableId", new AttributeValue(tableId));

			GetItemRequest request = new GetItemRequest()
					.withTableName(System.getenv("tables_table"))
					.withKey(keyToGet);

			GetItemResult result = dynamoDBClient.getItem(request);

			if (result.getItem() != null && !result.getItem().isEmpty()) {
				Map<String, Object> item = ItemUtils.toSimpleMapValue(result.getItem());
				response.put("statusCode", 200);
				response.put("body", item);
			} else {
				response.put("statusCode", 404);
				response.put("body", "Table not found with id: " + tableId);
			}
		} catch (Exception e) {
			logger.log("Error fetching table by ID: " + e.getMessage());
			response.put("statusCode", 500);
			response.put("body", "Internal server error.");
		}

		return response;
	}


	private Map<String, Object> handleGetReservations(LambdaLogger logger) {
		ScanRequest scanRequest = new ScanRequest().withTableName(System.getenv("reservations_table"));
		ScanResult result = dynamoDBClient.scan(scanRequest);
		List<Map<String, Object>> items = new ArrayList<>();
		result.getItems().forEach(item -> items.add(ItemUtils.toSimpleMapValue(item)));
		return buildResponse(200, items);
	}

	private Map<String, Object> handleCreateTable(Map<String, Object> event, LambdaLogger logger) {
		return new HashMap<>();
	}

	private Map<String, Object> handleCreateReservation(Map<String, Object> event, LambdaLogger logger) {
		return new HashMap<>();
	}

	private Map<String, Object> buildResponse(int statusCode, Object body) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("body", body);
		return response;
	}
}
