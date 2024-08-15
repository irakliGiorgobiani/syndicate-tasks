package com.task08;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;

import java.util.HashMap;
import java.util.Map;

@LambdaHandler(
    lambdaName = "api_handler",
	roleName = "api_handler-role",
		layers = {"weather-api"},
	isPublishVersion = false,
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
//@LambdaLayer(layerName = "weather-api",
//		libraries = {"weather-layer/java/lib/weather-lambda-java-1.0-SNAPSHOT.jar"},
//		runtime = DeploymentRuntime.JAVA11,
//		architectures = {Architecture.ARM64},
//		artifactExtension = ArtifactExtension.ZIP
//)
@LambdaUrlConfig
public class ApiHandler implements RequestHandler<Object, Map<String, Object>> {

	public Map<String, Object> handleRequest(Object request, Context context) {
		Map<String, Object> result = new HashMap<>();
		String apiUrl = "https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m,wind_speed_10m&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m";

		try {
			URL url = new URL(apiUrl);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");

			try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
				StringBuilder response = new StringBuilder();
				String line;
				while ((line = in.readLine()) != null) {
					response.append(line);
				}

				result.put("statusCode", 200);
				result.put("body", response.toString());
			}
		} catch (Exception e) {
			result.put("statusCode", 500);
			result.put("body", "Error: " + e.getMessage());
		}

		return result;
	}
}
