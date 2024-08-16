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
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Scanner;
import java.util.List;
import java.util.ArrayList;

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
public class Processor implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayV2HTTPResponse> {

	private final AmazonDynamoDB amazonDynamoDBClient = AmazonDynamoDBClientBuilder.defaultClient();
	private final DynamoDB dynamoDB = new DynamoDB(amazonDynamoDBClient);
	private final String tableName = System.getenv("target_table");

	@Override
	public APIGatewayV2HTTPResponse handleRequest(APIGatewayProxyRequestEvent request, Context context) {
		APIGatewayV2HTTPResponse response;
		try {
			String weatherData = fetchWeatherData();
			ObjectMapper objectMapper = new ObjectMapper();

			JsonNode forecastNode = objectMapper.readTree(weatherData);

			Map<String, Object> forecastMap = new HashMap<>();
			forecastMap.put("elevation", forecastNode.path("elevation").asDouble());
			forecastMap.put("generationtime_ms", forecastNode.path("generationtime_ms").asDouble());
			forecastMap.put("latitude", forecastNode.path("latitude").asDouble());
			forecastMap.put("longitude", forecastNode.path("longitude").asDouble());
			forecastMap.put("timezone", forecastNode.path("timezone").asText());
			forecastMap.put("timezone_abbreviation", forecastNode.path("timezone_abbreviation").asText());
			forecastMap.put("utc_offset_seconds", forecastNode.path("utc_offset_seconds").asInt());

			JsonNode hourlyNode = forecastNode.path("hourly");
			JsonNode timeNode = hourlyNode.path("time");
			JsonNode temperatureNode = hourlyNode.path("temperature_2m");

			if (timeNode.isArray() && temperatureNode.isArray()) {
				List<String> times = new ArrayList<>();
				List<Double> temperatures = new ArrayList<>();

				for (JsonNode time : timeNode) {
					times.add(time.asText());
				}

				for (JsonNode temp : temperatureNode) {
					temperatures.add(temp.asDouble());
				}

				Map<String, Object> hourlyMap = new HashMap<>();
				hourlyMap.put("time", times);
				hourlyMap.put("temperature_2m", temperatures);
				forecastMap.put("hourly", hourlyMap);
			} else {
				throw new RuntimeException("Unexpected format in hourly data");
			}

			JsonNode hourlyUnitsNode = forecastNode.path("hourly_units");
			Map<String, String> hourlyUnitsMap = new HashMap<>();
			hourlyUnitsMap.put("time", hourlyUnitsNode.path("time").asText());
			hourlyUnitsMap.put("temperature_2m", hourlyUnitsNode.path("temperature_2m").asText());
			forecastMap.put("hourly_units", hourlyUnitsMap);

			String id = UUID.randomUUID().toString();

			Table table = dynamoDB.getTable(tableName);
			Item item = new Item()
					.withPrimaryKey("id", id)
					.withMap("forecast", forecastMap);
			table.putItem(item);

			// Build the successful response
			response = APIGatewayV2HTTPResponse.builder()
					.withStatusCode(200)
					.withBody("Weather data successfully processed and stored.")
					.build();

		} catch (Exception ex) {
			context.getLogger().log("Error: " + ex.getMessage());
			response = APIGatewayV2HTTPResponse.builder()
					.withStatusCode(500)
					.withBody("{\"statusCode\": 500, \"message\": \"Internal Server Error\"} " + ex.getMessage())
					.build();
		}

		return response;
	}

	private String fetchWeatherData() throws Exception {
		URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=50.4375&longitude=30.5&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m");

		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");

		Scanner scanner = new Scanner((InputStream) conn.getContent());
		StringBuilder response = new StringBuilder();

		while (scanner.hasNext()) {
			response.append(scanner.nextLine());
		}

		scanner.close();
		return response.toString();
	}
}