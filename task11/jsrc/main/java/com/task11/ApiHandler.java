package com.task11;

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.AdminCreateUserRequest;
import com.amazonaws.services.cognitoidp.model.AdminInitiateAuthRequest;
import com.amazonaws.services.cognitoidp.model.AdminInitiateAuthResult;
import com.amazonaws.services.cognitoidp.model.AdminSetUserPasswordRequest;
import com.amazonaws.services.cognitoidp.model.AttributeType;
import com.amazonaws.services.cognitoidp.model.AuthFlowType;
import com.amazonaws.services.cognitoidp.model.ListUserPoolClientsRequest;
import com.amazonaws.services.cognitoidp.model.ListUserPoolClientsResult;
import com.amazonaws.services.cognitoidp.model.ListUserPoolsRequest;
import com.amazonaws.services.cognitoidp.model.ListUserPoolsResult;
import com.amazonaws.services.cognitoidp.model.MessageActionType;
import com.amazonaws.services.cognitoidp.model.UserPoolClientDescription;
import com.amazonaws.services.cognitoidp.model.UserPoolDescriptionType;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemUtils;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
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

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@LambdaHandler(
		lambdaName = "api_handler",
		roleName = "api_handler-role"
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

	public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
		LambdaLogger logger = context.getLogger();
		logger.log("Request: " + request.toString());

		logger.log("tables_table: " + System.getenv("tables_table"));
		logger.log("reservations_table: " + System.getenv("reservations_table"));
		logger.log("booking_userpool: " + System.getenv("booking_userpool"));

		Map<String, Object> responseMap = new HashMap<>();
		String path = (String) request.get("path");
		String httpMethod = (String) request.get("httpMethod");

		try {
			if (path.startsWith("/tables") && httpMethod.equalsIgnoreCase("GET")) {
				if (path.equals("/tables")) {
					responseMap = handleGetTables(logger);
				} else if (path.matches("/tables/\\d+")) {
					String tableId = path.substring("/tables/".length());
					responseMap = handleGetTableById(tableId, logger);
				} else {
					responseMap.put("statusCode", 400);
					responseMap.put("body", "Invalid table ID.");
				}
			} else if (path.startsWith("/tables") && httpMethod.equalsIgnoreCase("POST")) {
				responseMap = handleCreateTable(request, logger);
			} else if (path.equals("/signup") && "POST".equalsIgnoreCase(httpMethod)) {
				responseMap = handleSignup(request, logger);
			} else if (path.equals("/signin") && "POST".equalsIgnoreCase(httpMethod)) {
				responseMap = handleSignin(request, logger);
			} else if (path.equals("/reservations")) {
				if ("POST".equalsIgnoreCase(httpMethod)) {
					responseMap = handleCreateReservation(request, logger);
				} else if ("GET".equalsIgnoreCase(httpMethod)) {
					responseMap = handleGetReservations(logger);
				}
			} else {
				responseMap.put("statusCode", 400);
				responseMap.put("body", "Invalid path.");
			}
		} catch (Exception e) {
			logger.log("Error: " + e.getMessage());
			responseMap.put("statusCode", 500);
			responseMap.put("body", "Internal server error.");
		}

		Map<String, String> headers = new HashMap<>();
		headers.put("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");
		headers.put("Access-Control-Allow-Origin", "*");
		headers.put("Access-Control-Allow-Methods", "*");
		headers.put("Accept-Version", "*");

		responseMap.put("headers", headers);

		return responseMap;
	}

	private Map<String, Object> handleSignup(Map<String, Object> event, LambdaLogger logger) {
		Map<String, Object> response = new HashMap<>();
		ObjectMapper objectMapper = new ObjectMapper();
		try {
			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);
			logger.log("signUp was called");

			String email = String.valueOf(body.get("email"));
			String password = String.valueOf(body.get("password"));

			if (!validEmail(email)) {
				logger.log("Email is invalid");
				throw new Exception("Email is invalid");
			}

			if (!validPassword(password)) {
				logger.log("Password is invalid");
				throw new Exception("Password is invalid");
			}

			logger.log("Looking up user pool ID for: " + System.getenv("booking_userpool"));
			String userPoolId = getUserPoolIdByName(System.getenv("booking_userpool"))
					.orElseThrow(() -> new IllegalArgumentException("No such user pool"));
			logger.log("Found user pool ID: " + userPoolId);

			AdminCreateUserRequest adminCreateUserRequest = new AdminCreateUserRequest()
					.withUserPoolId(userPoolId)
					.withUsername(email)
					.withUserAttributes(new AttributeType().withName("email").withValue(email))
					.withMessageAction(MessageActionType.SUPPRESS);
			logger.log("AdminCreateUserRequest: " + adminCreateUserRequest.toString());

			AdminSetUserPasswordRequest adminSetUserPassword = new AdminSetUserPasswordRequest()
					.withPassword(password)
					.withUserPoolId(userPoolId)
					.withUsername(email)
					.withPermanent(true);
			logger.log(adminSetUserPassword.toString());

			logger.log("Creating user in Cognito...");
			cognitoClient.adminCreateUser(adminCreateUserRequest);
			logger.log("User created successfully.");

			logger.log("Setting user password...");
			cognitoClient.adminSetUserPassword(adminSetUserPassword);
			logger.log("Password set successfully.");

			response.put("statusCode", 200);
			response.put("body", "User created successfully");

		} catch (Exception ex) {
			logger.log(ex.toString());
			response.put("statusCode", 400);
			response.put("body", ex.getMessage());
		}
		return response;
	}


	private Map<String, Object> handleSignin(Map<String, Object> event, LambdaLogger logger) {
		logger.log("SignIn process started.");
		Map<String, Object> response = new HashMap<>();
		ObjectMapper objectMapper = new ObjectMapper();

		try {
			logger.log("Received event: " + event.toString());

			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);
			logger.log("Parsed body from event: " + body.toString());

			String email = String.valueOf(body.get("email"));
			String password = String.valueOf(body.get("password"));
			logger.log("Extracted email: " + email);
			logger.log("Extracted password: " + password);


			if (!validEmail(email)) {
				logger.log("Email validation failed for: " + email);
				throw new Exception("Email is invalid");
			}
			logger.log("Email validation passed for: " + email);

			if (!validPassword(password)) {
				logger.log("Password validation failed.");
				throw new Exception("Password is invalid");
			}
			logger.log("Password validation passed.");

			String userPoolId = getUserPoolIdByName(System.getenv("booking_userpool"))
					.orElseThrow(() -> new IllegalArgumentException("No such user pool"));
			logger.log("Retrieved user pool ID: " + userPoolId);

			String clientId = getClientIdByUserPoolName(System.getenv("booking_userpool"))
					.orElseThrow(() -> new IllegalArgumentException("No such client ID"));
			logger.log("Retrieved client ID: " + clientId);

			Map<String, String> authParams = new HashMap<>();
			authParams.put("USERNAME", email);
			authParams.put("PASSWORD", password);
			logger.log("Authentication parameters: " + authParams);

			AdminInitiateAuthRequest authRequest = new AdminInitiateAuthRequest()
					.withAuthFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
					.withUserPoolId(userPoolId)
					.withClientId(clientId)
					.withAuthParameters(authParams);
			logger.log("AdminInitiateAuthRequest: " + authRequest.toString());

			AdminInitiateAuthResult result = cognitoClient.adminInitiateAuth(authRequest);
			logger.log("AdminInitiateAuthResult: " + result.toString());

			if (result.getAuthenticationResult() != null) {
				String accessToken = result.getAuthenticationResult().getIdToken();
				logger.log("Authentication successful. AccessToken: " + accessToken);

				Map<String, Object> jsonResponse = new HashMap<>();
				jsonResponse.put("accessToken", accessToken);

				response.put("statusCode", 200);
				response.put("body", objectMapper.writeValueAsString(jsonResponse));
				logger.log("Response JSON: " + jsonResponse);
			} else {
				logger.log("Authentication failed, no tokens returned.");
				throw new Exception("Authentication failed, no tokens returned.");
			}
		} catch (Exception ex) {
			logger.log("Exception encountered: " + ex.getMessage());
			response.put("statusCode", 400);
			response.put("body", ex.getMessage());
		}

		logger.log("SignIn process completed.");
		return response;
	}


	private Map<String, Object> handleGetTables(LambdaLogger logger) {
		logger.log("getTables was called");
		Map<String, Object> response = new HashMap<>();
		ObjectMapper objectMapper = new ObjectMapper();

		try {
			AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
					.withRegion(System.getenv("region"))
					.build();
			ScanRequest scanRequest = new ScanRequest().withTableName(System.getenv("tables_table"));
			ScanResult scanResult = ddb.scan(scanRequest);
			logger.log("ScanResult: " + scanResult.toString());

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
			logger.log("Tables List: " + tables.toString());

			tables.sort(Comparator.comparing(o -> (Integer) o.get("id")));

			Map<String, Object> jsonResponse = new HashMap<>();
			jsonResponse.put("tables", tables);
			logger.log("Response JSON: " + jsonResponse.toString());

			response.put("statusCode", 200);
			response.put("body", objectMapper.writeValueAsString(jsonResponse));
		} catch (Exception e) {
			logger.log("Exception: " + e.getMessage());
			response.put("statusCode", 400);
			response.put("body", e.getMessage());
		}

		return response;
	}

	private Map<String, Object> handleGetTableById(String tableId, LambdaLogger logger) {
		logger.log("getTableById was called");
		Map<String, Object> response = new HashMap<>();
		ObjectMapper objectMapper = new ObjectMapper();

		try {
			AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
					.withRegion(System.getenv("region"))
					.build();
			ScanRequest scanRequest = new ScanRequest().withTableName(System.getenv("tables_table"));
			ScanResult scanResult = ddb.scan(scanRequest);
			logger.log("ScanResult: " + scanResult.toString());

			Map<String, AttributeValue> table = null;

			for (Map<String, AttributeValue> item : scanResult.getItems()) {
				int existingId = Integer.parseInt(item.get("id").getS().trim().replaceAll("\"", ""));
				int requiredId = Integer.parseInt(tableId.trim().replaceAll("\"", ""));
				if (existingId == requiredId) {
					table = item;
					break;
				}
			}

			if (table != null) {
				Map<String, Object> jsonResponse = ItemUtils.toSimpleMapValue(table);
				jsonResponse.replace("id", Integer.parseInt((String) jsonResponse.get("id")));

				logger.log("Found table: " + jsonResponse.toString());
				response.put("statusCode", 200);
				response.put("body", objectMapper.writeValueAsString(jsonResponse));
			} else {
				logger.log("Table not found with ID: " + tableId);
				response.put("statusCode", 404);
				response.put("body", "Table not found");
			}
		} catch (Exception e) {
			logger.log("Exception encountered: " + e.getMessage());
			response.put("statusCode", 400);
			response.put("body", e.getMessage());
		}

		return response;
	}

	private Map<String, Object> handleCreateTable(Map<String, Object> event, LambdaLogger logger) {
		logger.log("postTable was called");
		Map<String, Object> response = new HashMap<>();
		ObjectMapper objectMapper = new ObjectMapper();
		AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();

		try {
			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);
			logger.log("Request body: " + body.toString());

			String id = String.valueOf(body.get("id"));
			int number = (Integer) body.get("number");
			int places = (Integer) body.get("places");
			boolean isVip = (Boolean) body.get("isVip");
			int minOrder = -1;
			if (body.containsKey("minOrder")) {
				minOrder = (Integer) body.get("minOrder");
			}

			Item item = new Item()
					.withString("id", id)
					.withInt("number", number)
					.withInt("places", places)
					.withBoolean("isVip", isVip);
			if (minOrder != -1) {
				item.withInt("minOrder", minOrder);
			}
			logger.log("DynamoDB item: " + item.toString());

			ddb.putItem(System.getenv("tables_table"), ItemUtils.toAttributeValues(item));

			Map<String, Object> jsonResponse = new HashMap<>();
			jsonResponse.put("id", Integer.parseInt(id));
			logger.log("Response JSON: " + jsonResponse.toString());

			response.put("statusCode", 200);
			response.put("body", objectMapper.writeValueAsString(jsonResponse));
		} catch (Exception ex) {
			logger.log("Exception encountered: " + ex.getMessage());
			response.put("statusCode", 400);
			response.put("body", ex.getMessage());
		}

		return response;
	}

	private Map<String, Object> handleCreateReservation(Map<String, Object> event, LambdaLogger logger) {
		logger.log("postReservation was called");
		Map<String, Object> response = new HashMap<>();
		ObjectMapper objectMapper = new ObjectMapper();
		AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();

		try {
			Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);
			logger.log("Request body: " + body.toString());

			String reservationId = UUID.randomUUID().toString();
			String tableNumber = String.valueOf(body.get("tableNumber"));
			String clientName = String.valueOf(body.get("clientName"));
			String phoneNumber = String.valueOf(body.get("phoneNumber"));
			String date = String.valueOf(body.get("date"));
			String slotTimeStart = String.valueOf(body.get("slotTimeStart"));
			String slotTimeEnd = String.valueOf(body.get("slotTimeEnd"));

			String reservationsTable = System.getenv("reservations_table");
			logger.log("Environment variable 'reservations_table': " + reservationsTable);

			if (reservationsTable == null || reservationsTable.isEmpty()) {
				throw new IllegalArgumentException("Environment variable 'reservations_table' is not set or is empty.");
			}

			Item item = new Item()
					.withString("id", reservationId)
					.withString("tableNumber", tableNumber)
					.withString("clientName", clientName)
					.withString("phoneNumber", phoneNumber)
					.withString("date", date)
					.withString("slotTimeStart", slotTimeStart)
					.withString("slotTimeEnd", slotTimeEnd);

			logger.log("Reservation item: " + item.toString());

			if (!doesTaleExist(ddb, System.getenv("tables_table"), tableNumber, logger)) {
				response.put("statusCode", 400);
				response.put("body", "Table does not exist");
				logger.log("Table does not exist");
				return response;
			}

			if (isReservationOverlapping(ddb, reservationsTable, tableNumber, date, slotTimeStart, slotTimeEnd)) {
				response.put("statusCode", 400);
				response.put("body", "Reservation overlaps with an existing reservation");
				logger.log("Reservation overlaps with an existing reservation");
				return response;
			}

			ddb.putItem(reservationsTable, ItemUtils.toAttributeValues(item));

			Map<String, Object> jsonResponse = new HashMap<>();
			jsonResponse.put("reservationId", reservationId);
			logger.log("Response JSON: " + jsonResponse);

			response.put("statusCode", 200);
			response.put("body", objectMapper.writeValueAsString(jsonResponse));
		} catch (Exception ex) {
			logger.log("Exception encountered: " + ex.getMessage());
			response.put("statusCode", 400);
			response.put("body", ex.getMessage());
		}

		return response;
	}

	public boolean doesTaleExist(AmazonDynamoDB ddb, String tableName, String tableNumber, LambdaLogger logger) {
		ScanResult scanResult = ddb.scan(new ScanRequest().withTableName(tableName));

		for (Map<String, AttributeValue> item : scanResult.getItems()) {
			if (tableNumber.equals(item.get("number").getN())) {
				logger.log("Table exists, number: " + tableNumber);
				return true;
			}
		}
		return false;
	}

	public boolean isReservationOverlapping(AmazonDynamoDB ddb, String tableName, String tableNumber, String date, String slotTimeStart, String slotTimeEnd) {
		ScanResult scanResult = ddb.scan(new ScanRequest().withTableName(tableName));
		for (Map<String, AttributeValue> item : scanResult.getItems()) {
			String existingTableNumber = item.get("tableNumber").getS();
			String existingDate = item.get("date").getS();

			if (tableNumber.equals(existingTableNumber) && date.equals(existingDate)) {
				String existingSlotTimeStart = item.get("slotTimeStart").getS();
				String existingSlotTimeEnd = item.get("slotTimeEnd").getS();

				return timeOverlap(slotTimeStart, slotTimeEnd, existingSlotTimeStart, existingSlotTimeEnd);
			}
		}

		return false;
	}

	private Map<String, Object> handleGetReservations(LambdaLogger logger) {
		logger.log("getReservations was called");
		Map<String, Object> response = new HashMap<>();
		ObjectMapper objectMapper = new ObjectMapper();

		try {
			AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
					.withRegion(System.getenv("region"))
					.build();

			ScanRequest scanRequest = new ScanRequest().withTableName(System.getenv("reservations_table"));
			ScanResult scanResult = ddb.scan(scanRequest);
			logger.log("ScanResult: " + scanResult.toString());

			List<Map<String, Object>> reservations = new ArrayList<>();
			for (Map<String, AttributeValue> item : scanResult.getItems()) {
				Map<String, Object> reservation = new LinkedHashMap<>();
				reservation.put("tableNumber", Integer.parseInt(item.get("tableNumber").getS()));
				reservation.put("clientName", item.get("clientName").getS());
				reservation.put("phoneNumber", item.get("phoneNumber").getS());
				reservation.put("date", item.get("date").getS());
				reservation.put("slotTimeStart", item.get("slotTimeStart").getS());
				reservation.put("slotTimeEnd", item.get("slotTimeEnd").getS());

				reservations.add(reservation);
			}

			logger.log("Reservations: " + reservations.toString());

			Map<String, Object> jsonResponse = new HashMap<>();
			jsonResponse.put("reservations", reservations);
			logger.log("Response JSON: " + jsonResponse.toString());

			response.put("statusCode", 200);
			response.put("body", objectMapper.writeValueAsString(jsonResponse));
		} catch (Exception ex) {
			logger.log("Exception encountered: " + ex.getMessage());
			response.put("statusCode", 400);
			response.put("body", ex.getMessage());
		}

		return response;
	}

	private boolean timeOverlap(String slotTimeStart, String slotTimeEnd, String existingSlotTimeStart, String existingSlotTimeEnd) {

		LocalTime start = LocalTime.parse(slotTimeStart);
		LocalTime end = LocalTime.parse(slotTimeEnd);
		LocalTime existingStart = LocalTime.parse(existingSlotTimeStart);
		LocalTime existingEnd = LocalTime.parse(existingSlotTimeEnd);

		return (start.isBefore(existingEnd) && end.isAfter(existingStart));
	}

	public static boolean validPassword(String password) {
		if (password == null) {
			return false;
		}

		return password.length() >= 8 &&
				password.length() <= 20 &&
				password.matches(".*[A-Z].*") &&
				password.matches(".*[a-z].*") &&
				password.matches(".*\\d.*") &&
				password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");
	}

	public static boolean validEmail(String email) {
		final String EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$";
		final Pattern pattern = Pattern.compile(EMAIL_PATTERN);
		if (email == null) {
			return false;
		}

		Matcher matcher = pattern.matcher(email);
		return matcher.matches();
	}

	public Optional<String> getUserPoolIdByName(String userPoolName) {
		String nextToken = null;

		do {
			ListUserPoolsRequest listUserPoolsRequest = new ListUserPoolsRequest()
					.withMaxResults(60)
					.withNextToken(nextToken);

			ListUserPoolsResult listUserPoolsResult = cognitoClient.listUserPools(listUserPoolsRequest);

			for (UserPoolDescriptionType pool : listUserPoolsResult.getUserPools()) {
				if (pool.getName().equals(userPoolName)) {
					return Optional.of(pool.getId());
				}
			}

			nextToken = listUserPoolsResult.getNextToken();
		} while (nextToken != null);

		return Optional.empty();
	}

	public Optional<String> getClientIdByUserPoolName(String userPoolName) {
		String userPoolId = getUserPoolIdByName(userPoolName).get();

		ListUserPoolClientsRequest listUserPoolClientsRequest = new ListUserPoolClientsRequest().withUserPoolId(userPoolId);
		ListUserPoolClientsResult listUserPoolClientsResult = cognitoClient.listUserPoolClients(listUserPoolClientsRequest);

		for (UserPoolClientDescription client : listUserPoolClientsResult.getUserPoolClients()) {
			return Optional.of(client.getClientId());
		}

		return Optional.empty();
	}
}