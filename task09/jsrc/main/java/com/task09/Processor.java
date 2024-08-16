package com.task09;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.TracingMode;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.model.RetentionSetting;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
		lambdaName = "processor",
		roleName = "processor-role",
		isPublishVersion = false,
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
		tracingMode = TracingMode.Active
)
@LambdaUrlConfig
@EnvironmentVariables(
		@EnvironmentVariable(key = "target_table", value = "${target_table}")
)
public class Processor implements RequestHandler<Object, Map<String, Object>> {

	private static final String WEATHER_API_URL = "https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m,wind_speed_10m&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m";

	private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
	private final DynamoDB dynamoDB = new DynamoDB(client);
	private final Table weatherTable = dynamoDB.getTable(System.getenv("target_table"));

	@Override
	public Map<String, Object> handleRequest(Object request, Context context) {
		Map<String, Object> result = new HashMap<>();
		try {
			HttpClient httpClient = HttpClient.newHttpClient();
			HttpRequest httpRequest = HttpRequest.newBuilder()
					.uri(URI.create(WEATHER_API_URL))
					.build();
			HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

			ObjectMapper mapper = new ObjectMapper();
			JsonNode rootNode = mapper.readTree(response.body());

			JsonNode forecastNode = rootNode.get("hourly");

			Map<String, AttributeValue> item = new HashMap<>();
			item.put("id", new AttributeValue(UUID.randomUUID().toString()));
			item.put("forecast", new AttributeValue(forecastNode.toString()));

			PutItemRequest putItemRequest = new PutItemRequest()
					.withTableName(System.getenv("target_table"))
					.withItem(item);

			PutItemResult putItemResult = client.putItem(putItemRequest);

			result.put("status", "success");
			result.put("message", "Weather data successfully stored in DynamoDB.");
		} catch (IOException | InterruptedException e) {
			context.getLogger().log("Error: " + e.getMessage());
			result.put("status", "error");
			result.put("message", "Error processing weather data.");
		}

		return result;
	}
}
