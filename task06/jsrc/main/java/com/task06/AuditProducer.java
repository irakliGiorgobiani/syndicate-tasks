package com.task06;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.DynamoDbTriggerEventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(lambdaName = "audit_producer",
		roleName = "audit_producer-role",
		isPublishVersion = false,
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DynamoDbTriggerEventSource(
		targetTable = "Configuration",
		batchSize = 1
)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "target_table", value = "${target_table}")
})
public class AuditProducer implements RequestHandler<DynamodbEvent, String> {

	private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.standard().build();
	private final DynamoDB dynamoDBService = new DynamoDB(dynamoClient);
	private final Table auditLogTable = dynamoDBService.getTable(System.getenv("target_table"));

	@Override
	public String handleRequest(DynamodbEvent dynamoDbEvent, Context context) {
		dynamoDbEvent.getRecords().forEach(record -> processRecord(record));
		return "Execution Completed";
	}

	private void processRecord(DynamodbEvent.DynamodbStreamRecord record) {
		String operationType = record.getEventName();
		switch (operationType) {
			case "INSERT":
				logNewItem(record.getDynamodb().getNewImage());
				break;
			case "MODIFY":
				logModifiedItem(record.getDynamodb().getNewImage(), record.getDynamodb().getOldImage());
				break;
			default:
				break;
		}
	}

	private void logNewItem(Map<String, AttributeValue> newAttributes) {
		String itemKey = newAttributes.get("key").getS();
		int itemValue = Integer.parseInt(newAttributes.get("value").getN());

		Map<String, Object> itemData = new HashMap<>();
		itemData.put("key", itemKey);
		itemData.put("value", itemValue);

		Item logEntry = new Item()
				.withPrimaryKey("logId", UUID.randomUUID().toString())
				.withString("itemKey", itemKey)
				.withString("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)))
				.withMap("newItemDetails", itemData);

		auditLogTable.putItem(logEntry);
	}

	private void logModifiedItem(Map<String, AttributeValue> newAttributes, Map<String, AttributeValue> oldAttributes) {
		String itemKey = newAttributes.get("key").getS();
		int previousValue = Integer.parseInt(oldAttributes.get("value").getN());
		int updatedValue = Integer.parseInt(newAttributes.get("value").getN());

		if (previousValue != updatedValue) {
			Item modificationLog = new Item()
					.withPrimaryKey("logId", UUID.randomUUID().toString())
					.withString("itemKey", itemKey)
					.withString("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)))
					.withString("modifiedAttribute", "value")
					.withInt("previousValue", previousValue)
					.withInt("updatedValue", updatedValue);
			auditLogTable.putItem(modificationLog);
		}
	}
}
