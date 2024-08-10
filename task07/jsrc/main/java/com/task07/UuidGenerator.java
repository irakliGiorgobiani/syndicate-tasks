package com.task07;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.RuleEventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(lambdaName = "uuid_generator",
		roleName = "uuid_generator-role",
		isPublishVersion = false,
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@RuleEventSource(
		targetRule = "uuid_trigger"
)
@DependsOn(
		name = "uuid_trigger",
		resourceType = ResourceType.CLOUDWATCH_RULE
)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "target_bucket", value = "${target_bucket}"),
		@EnvironmentVariable(key = "region", value = "${region}")
})
public class UuidGenerator implements RequestHandler<ScheduledEvent, Map<String, Object>> {

	private static final String BUCKET = System.getenv("target_bucket");

	@Override
	public Map<String, Object> handleRequest(ScheduledEvent event, Context context) {
		AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();

		String timeKey = Instant.now().toString();

		List<String> generatedUuids = generateUuids(10);

		String fileContent = formatJsonContent(generatedUuids);
		File outputFile = new File("/tmp/AWS.txt");

		if (!writeToFile(outputFile, fileContent, context)) {
			return createErrorResponse("Error writing to file");
		}

		if (!uploadToS3(s3Client, outputFile, timeKey, context)) {
			return createErrorResponse("Error uploading file to S3");
		}

		return createSuccessResponse();
	}

	private List<String> generateUuids(int count) {
		List<String> uuids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			uuids.add(UUID.randomUUID().toString());
		}
		return uuids;
	}

	private String formatJsonContent(List<String> uuids) {
		return "{\n  \"ids\": [\n    \"" + String.join("\",\n    \"", uuids) + "\"\n  ]\n}";
	}

	private boolean writeToFile(File file, String content, Context context) {
		try (FileWriter writer = new FileWriter(file)) {
			writer.write(content);
			context.getLogger().log("File created successfully: " + file.getAbsolutePath());
			return true;
		} catch (IOException e) {
			context.getLogger().log("Failed to write file: " + e.getMessage());
			return false;
		}
	}

	private boolean uploadToS3(AmazonS3 s3Client, File file, String key, Context context) {
		try {
			s3Client.putObject(new PutObjectRequest(BUCKET, key, file));
			context.getLogger().log("File uploaded to S3: " + BUCKET + "/" + key);
			return true;
		} catch (Exception e) {
			context.getLogger().log("Failed to upload to S3: " + e.getMessage());
			return false;
		}
	}

	private Map<String, Object> createErrorResponse(String message) {
		Map<String, Object> errorResponse = new HashMap<>();
		errorResponse.put("statusCode", 500);
		errorResponse.put("body", message);
		return errorResponse;
	}

	private Map<String, Object> createSuccessResponse() {
		Map<String, Object> successResponse = new HashMap<>();
		successResponse.put("statusCode", 200);
		successResponse.put("body", "UUIDs generated and uploaded to S3 bucket successfully");
		return successResponse;
	}
}
