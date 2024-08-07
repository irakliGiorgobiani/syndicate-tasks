package com.task02;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.RetentionSetting;

@LambdaHandler(
		lambdaName = "hello_world",
		roleName = "hello_world-role",
		isPublishVersion = false,
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig
public class HelloWorld implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private static int count = 0;

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

		System.out.println(request.getPath());

		if (count == 0) {
			response.setStatusCode(200);
			response.setBody("{\"statusCode\": 200, \"message\": \"Hello from Lambda\"}");
			count++;
		} else {
			response.setStatusCode(400);
			response.setBody("{\"statusCode\": 400, \"message\": \"Bad request syntax or unsupported method. Request path: /cmtr-10f5338f. HTTP method: GET\"}");
		}

		return response;
	}
}
