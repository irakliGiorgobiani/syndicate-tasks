package com.task10;

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.*;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
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
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.*;
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
	private final ObjectMapper objectMapper = new ObjectMapper();

	public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
		LambdaLogger logger = context.getLogger();
		logger.log("Request: " + request.toString());

		Map<String, Object> responseMap = new HashMap<>();
		String path = (String) request.get("path");
		String httpMethod = (String) request.get("httpMethod");

		try {
			if (path.startsWith("/tables")) {
				responseMap = handleTablesRequest(path, httpMethod, request, logger);
			} else if (path.equals("/signup") && "POST".equalsIgnoreCase(httpMethod)) {
				responseMap = handleSignup(request, logger);
			} else if (path.equals("/signin") && "POST".equalsIgnoreCase(httpMethod)) {
				responseMap = handleSignin(request, logger);
			} else if (path.startsWith("/reservations")) {
				responseMap = handleReservationsRequest(httpMethod, request, logger);
			} else {
				responseMap.put("statusCode", 400);
				responseMap.put("body", "Invalid path.");
			}
		} catch (Exception e) {
			logger.log("Error: " + e.getMessage());
			responseMap.put("statusCode", 500);
			responseMap.put("body", "Internal server error.");
		}

		return responseMap;
	}

	private Map<String, Object> handleTablesRequest(String path, String httpMethod, Map<String, Object> request, LambdaLogger logger) {
		if ("GET".equalsIgnoreCase(httpMethod)) {
			if (path.equals("/tables")) {
				return handleGetTables(logger);
			} else if (path.matches("/tables/\\d+")) {
				String tableId = path.substring("/tables/".length());
				return handleGetTableById(tableId, logger);
			} else {
				return createErrorResponse(400, "Invalid table ID.");
			}
		} else if ("POST".equalsIgnoreCase(httpMethod)) {
			return handleCreateTable(request, logger);
		}
		return createErrorResponse(400, "Invalid HTTP method.");
	}

	private Map<String, Object> handleReservationsRequest(String httpMethod, Map<String, Object> request, LambdaLogger logger) {
		if ("POST".equalsIgnoreCase(httpMethod)) {
			return handleCreateReservation(request, logger);
		} else if ("GET".equalsIgnoreCase(httpMethod)) {
			return handleGetReservations(logger);
		}
		return createErrorResponse(400, "Invalid HTTP method.");
	}

	private Map<String, Object> handleSignup(Map<String, Object> event, LambdaLogger logger) {
		try {
			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);

			String email = (String) body.get("email");
			String password = (String) body.get("password");

			validateEmailAndPassword(email, password);

			String userPoolId = getUserPoolIdByName(System.getenv("booking_userpool"))
					.orElseThrow(() -> new IllegalArgumentException("No such user pool"));

			AdminCreateUserRequest createUserRequest = new AdminCreateUserRequest()
					.withUserPoolId(userPoolId)
					.withUsername(email)
					.withUserAttributes(new AttributeType().withName("email").withValue(email))
					.withMessageAction(MessageActionType.SUPPRESS);

			AdminSetUserPasswordRequest setPasswordRequest = new AdminSetUserPasswordRequest()
					.withPassword(password)
					.withUserPoolId(userPoolId)
					.withUsername(email)
					.withPermanent(true);

			cognitoClient.adminCreateUser(createUserRequest);
			cognitoClient.adminSetUserPassword(setPasswordRequest);

			return createSuccessResponse("User created successfully");

		} catch (Exception ex) {
			logger.log(ex.toString());
			return createErrorResponse(400, ex.getMessage());
		}
	}

	private Map<String, Object> handleSignin(Map<String, Object> inputEvent, LambdaLogger logger) {
		logger.log("Initiating sign-in process.");
		Map<String, Object> result = new HashMap<>();
		ObjectMapper jsonMapper = new ObjectMapper();

		try {
			logger.log("Event received: " + inputEvent);

			String eventBody = (String) inputEvent.get("body");
			if (eventBody == null) {
				throw new IllegalArgumentException("Event body is missing.");
			}

			Map<String, Object> parsedBody = jsonMapper.readValue(eventBody, Map.class);
			logger.log("Parsed event body: " + parsedBody);

			String emailAddress = (String) parsedBody.get("email");
			String userPassword = (String) parsedBody.get("password");
			logger.log("Email extracted: " + emailAddress);
			logger.log("Password extracted: " + userPassword);

			if (!validateEmailAndPassword(emailAddress, userPassword)) {
				logger.log("Email validation failed for: " + emailAddress + " Or Password validation failed");
				throw new IllegalArgumentException("Invalid email or password");
			}
			logger.log("Email and Password validated successfully.");

			String poolId = getUserPoolIdByName(System.getenv("booking_userpool"))
					.orElseThrow(() -> new IllegalArgumentException("User pool not found"));
			logger.log("User pool ID obtained: " + poolId);

			String appClientId = getClientIdByUserPoolName(System.getenv("booking_userpool"))
					.orElseThrow(() -> new IllegalArgumentException("Client ID not found"));
			logger.log("Client ID obtained: " + appClientId);

			Map<String, String> authenticationParams = new HashMap<>();
			authenticationParams.put("USERNAME", emailAddress);
			authenticationParams.put("PASSWORD", userPassword);
			logger.log("Auth parameters: " + authenticationParams);

			AdminInitiateAuthRequest authRequest = new AdminInitiateAuthRequest()
					.withAuthFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
					.withUserPoolId(poolId)
					.withClientId(appClientId)
					.withAuthParameters(authenticationParams);
			logger.log("Auth request details: " + authRequest);

			AdminInitiateAuthResult authResult = cognitoClient.adminInitiateAuth(authRequest);
			logger.log("Auth result details: " + authResult);

			if (authResult.getAuthenticationResult() != null) {
				String idToken = authResult.getAuthenticationResult().getIdToken();
				logger.log("Authentication successful. ID Token: " + idToken);

				Map<String, Object> successResponse = new HashMap<>();
				successResponse.put("accessToken", idToken);

				result.put("statusCode", 200);
				result.put("body", jsonMapper.writeValueAsString(successResponse));
				logger.log("Successful response: " + successResponse);
			} else {
				logger.log("Authentication failed: No tokens were returned.");
				throw new IllegalStateException("Failed to receive authentication tokens.");
			}
		} catch (IllegalArgumentException | JsonProcessingException ex) {
			logger.log("Error occurred: " + ex.getMessage());
			result.put("statusCode", 400);
			result.put("body", ex.getMessage());
		} catch (Exception ex) {
			logger.log("Unexpected error: " + ex.getMessage());
			result.put("statusCode", 500);
			result.put("body", "Internal server error");
		}

		logger.log("Sign-in process concluded.");
		return result;
	}


	private Map<String, Object> handleGetTables(LambdaLogger logger) {
		try {
			ScanRequest scanRequest = new ScanRequest().withTableName(System.getenv("tables_table"));
			ScanResult scanResult = dynamoDBClient.scan(scanRequest);

			List<Map<String, Object>> tables = new ArrayList<>();
			for (Map<String, AttributeValue> item : scanResult.getItems()) {
				Map<String, Object> table = new LinkedHashMap<>();
				table.put("id", Integer.parseInt(item.get("id").getS()));
				table.put("number", Integer.parseInt(item.get("number").getN()));
				table.put("places", Integer.parseInt(item.get("places").getN()));
				table.put("isVip", Boolean.parseBoolean(item.get("isVip").getBOOL().toString()));
				table.put("minOrder", item.containsKey("minOrder") ? Integer.parseInt(item.get("minOrder").getN()) : null);
				tables.add(table);
			}

			tables.sort(Comparator.comparing(o -> (Integer) o.get("id")));
			return createSuccessResponse(Map.of("tables", tables));

		} catch (Exception e) {
			logger.log("Exception: " + e.getMessage());
			return createErrorResponse(500, e.getMessage());
		}
	}

	private Map<String, Object> handleGetTableById(String tableId, LambdaLogger logger) {
		try {
			ScanRequest scanRequest = new ScanRequest().withTableName(System.getenv("tables_table"));
			ScanResult scanResult = dynamoDBClient.scan(scanRequest);

			Optional<Map<String, Object>> table = scanResult.getItems().stream()
					.filter(item -> tableId.equals(item.get("id").getS()))
					.map(item -> {
						Map<String, Object> resultMap = new HashMap<>();
						resultMap.put("id", Integer.parseInt(item.get("id").getS()));
						resultMap.put("number", Integer.parseInt(item.get("number").getN()));
						resultMap.put("places", Integer.parseInt(item.get("places").getN()));
						resultMap.put("isVip", Boolean.parseBoolean(item.get("isVip").getBOOL().toString()));
						resultMap.put("minOrder", item.containsKey("minOrder") ? Integer.parseInt(item.get("minOrder").getN()) : null);
						return resultMap;
					})
					.findFirst();

			return table.map(this::createSuccessResponse)
					.orElseGet(() -> createErrorResponse(404, "Table not found"));

		} catch (Exception e) {
			logger.log("Exception: " + e.getMessage());
			return createErrorResponse(500, e.getMessage());
		}
	}

	private Map<String, Object> handleCreateTable(Map<String, Object> event, LambdaLogger logger) {
		try {
			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);
			String tableName = (String) body.get("tableName");
			int tableNumber = (int) body.get("number");
			int places = (int) body.get("places");
			boolean isVip = (boolean) body.get("isVip");
			Integer minOrder = body.containsKey("minOrder") ? (Integer) body.get("minOrder") : null;

			Map<String, AttributeValue> item = new HashMap<>();
			item.put("id", new AttributeValue(UUID.randomUUID().toString()));
			item.put("number", new AttributeValue().withN(String.valueOf(tableNumber)));
			item.put("places", new AttributeValue().withN(String.valueOf(places)));
			item.put("isVip", new AttributeValue().withBOOL(isVip));

			if (minOrder != null) {
				item.put("minOrder", new AttributeValue().withN(String.valueOf(minOrder)));
			}

			PutItemRequest putItemRequest = new PutItemRequest()
					.withTableName(tableName)
					.withItem(item);

			dynamoDBClient.putItem(putItemRequest);

			return createSuccessResponse("Table created successfully");

		} catch (Exception e) {
			logger.log("Exception: " + e.getMessage());
			return createErrorResponse(500, e.getMessage());
		}
	}

	private Map<String, Object> handleCreateReservation(Map<String, Object> event, LambdaLogger logger) {
		try {
			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);
			String tableId = (String) body.get("tableId");
			String customerName = (String) body.get("customerName");
			String reservationTime = (String) body.get("reservationTime");

			Map<String, AttributeValue> item = new HashMap<>();
			item.put("reservationId", new AttributeValue(UUID.randomUUID().toString()));
			item.put("tableId", new AttributeValue().withS(tableId));
			item.put("customerName", new AttributeValue().withS(customerName));
			item.put("reservationTime", new AttributeValue().withS(reservationTime));

			PutItemRequest putItemRequest = new PutItemRequest()
					.withTableName(System.getenv("reservations_table"))
					.withItem(item);

			dynamoDBClient.putItem(putItemRequest);

			return createSuccessResponse("Reservation created successfully");

		} catch (Exception e) {
			logger.log("Exception: " + e.getMessage());
			return createErrorResponse(500, e.getMessage());
		}
	}

	private Map<String, Object> handleGetReservations(LambdaLogger logger) {
		try {
			ScanRequest scanRequest = new ScanRequest()
					.withTableName(System.getenv("reservations_table"));

			ScanResult scanResult = dynamoDBClient.scan(scanRequest);
			List<Map<String, Object>> reservations = new ArrayList<>();

			for (Map<String, AttributeValue> item : scanResult.getItems()) {
				Map<String, Object> reservation = new HashMap<>();
				reservation.put("reservationId", item.get("reservationId").getS());
				reservation.put("tableId", item.get("tableId").getS());
				reservation.put("customerName", item.get("customerName").getS());
				reservation.put("reservationTime", item.get("reservationTime").getS());
				reservations.add(reservation);
			}

			return createSuccessResponse(Map.of("reservations", reservations));

		} catch (Exception e) {
			logger.log("Exception: " + e.getMessage());
			return createErrorResponse(500, e.getMessage());
		}
	}


	private Optional<String> getUserPoolIdByName(String userPoolName) {
		try {
			ListUserPoolsRequest listUserPoolsRequest = new ListUserPoolsRequest().withMaxResults(60);
			ListUserPoolsResult listUserPoolsResult = cognitoClient.listUserPools(listUserPoolsRequest);

			return listUserPoolsResult.getUserPools().stream()
					.filter(pool -> userPoolName.equals(pool.getName()))
					.map(UserPoolDescriptionType::getId)
					.findFirst();

		} catch (Exception e) {
			log.error("Exception encountered while retrieving user pool ID", e);
			return Optional.empty();
		}
	}

	private Optional<String> getClientIdByUserPoolName(String userPoolName) {
		try {
			String userPoolId = getUserPoolIdByName(userPoolName).orElse(null);

			if (userPoolId != null) {
				ListUserPoolClientsRequest clientsRequest = new ListUserPoolClientsRequest()
						.withUserPoolId(userPoolId)
						.withMaxResults(10);

				return cognitoClient.listUserPoolClients(clientsRequest).getUserPoolClients().stream()
						.findFirst()
						.map(UserPoolClientDescription::getClientId);
			}
			return Optional.empty();

		} catch (Exception e) {
			log.error("Exception encountered while retrieving client ID", e);
			return Optional.empty();
		}
	}

	private boolean validateEmailAndPassword(String email, String password) {
		if (email == null || password == null) {
			throw new IllegalArgumentException("Email and password must not be null.");
		}
		if (email.isEmpty() || password.isEmpty()) {
			throw new IllegalArgumentException("Email and password must not be empty.");
		}
		if (!Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$").matcher(email).matches()) {
			throw new IllegalArgumentException("Invalid email format.");
		}

		return true;
	}

	private Map<String, Object> createSuccessResponse(Object body) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", 200);
		response.put("body", body);
		return response;
	}

	private Map<String, Object> createErrorResponse(int statusCode, String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("body", message);
		return response;
	}
}