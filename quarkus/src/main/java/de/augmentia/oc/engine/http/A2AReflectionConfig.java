package de.augmentia.oc.engine.http;

import io.quarkus.runtime.annotations.RegisterForReflection;

import org.a2aproject.sdk.spec.A2AClientError;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.A2AClientHTTPError;
import org.a2aproject.sdk.spec.A2AClientInvalidArgsError;
import org.a2aproject.sdk.spec.A2AClientInvalidStateError;
import org.a2aproject.sdk.spec.A2AClientJSONError;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.A2AException;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.A2AProtocolError;
import org.a2aproject.sdk.spec.A2AServerException;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentCardSignature;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.AuthenticationInfo;
import org.a2aproject.sdk.spec.AuthorizationCodeOAuthFlow;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.ClientCredentialsOAuthFlow;
import org.a2aproject.sdk.spec.Compat03Fields;
import org.a2aproject.sdk.spec.ContentTypeNotSupportedError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.DeleteTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.DeviceCodeOAuthFlow;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.ExtendedAgentCardNotConfiguredError;
import org.a2aproject.sdk.spec.ExtensionSupportRequiredError;
import org.a2aproject.sdk.spec.FileContent;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.GetExtendedAgentCardParams;
import org.a2aproject.sdk.spec.GetTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidAgentResponseError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.OAuth2SecurityScheme;
import org.a2aproject.sdk.spec.OAuthFlows;
import org.a2aproject.sdk.spec.OpenIdConnectSecurityScheme;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.PushNotificationNotSupportedError;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.spec.UpdateEvent;
import org.a2aproject.sdk.spec.VersionNotSupportedError;
import org.a2aproject.sdk.spec.util.CollectionCopies;
import org.a2aproject.sdk.spec.util.ErrorDetail;
import org.a2aproject.sdk.spec.util.PageToken;
import org.a2aproject.sdk.spec.util.Utils;

/** Registers all A2A spec records for reflection in native images. */
@RegisterForReflection(targets = {
            A2AClientError.class,
            A2AClientException.class,
            A2AClientHTTPError.class,
            A2AClientInvalidArgsError.class,
            A2AClientInvalidStateError.class,
            A2AClientJSONError.class,
            A2AError.class,
            A2AErrorCodes.class,
            A2AException.class,
            A2AMethods.class,
            A2AProtocolError.class,
            A2AServerException.class,
            APIKeySecurityScheme.class,
            AgentCapabilities.class,
            AgentCard.class,
            AgentCardSignature.class,
            AgentExtension.class,
            AgentInterface.class,
            AgentProvider.class,
            AgentSkill.class,
            Artifact.class,
            AuthenticationInfo.class,
            AuthorizationCodeOAuthFlow.class,
            CancelTaskParams.class,
            ClientCredentialsOAuthFlow.class,
            Compat03Fields.class,
            ContentTypeNotSupportedError.class,
            DataPart.class,
            DeleteTaskPushNotificationConfigParams.class,
            DeviceCodeOAuthFlow.class,
            Event.class,
            EventKind.class,
            ExtendedAgentCardNotConfiguredError.class,
            ExtensionSupportRequiredError.class,
            FileContent.class,
            FilePart.class,
            FileWithBytes.class,
            FileWithUri.class,
            GetExtendedAgentCardParams.class,
            GetTaskPushNotificationConfigParams.class,
            HTTPAuthSecurityScheme.class,
            InternalError.class,
            InvalidAgentResponseError.class,
            InvalidParamsError.class,
            InvalidRequestError.class,
            JSONParseError.class,
            Legacy_0_3_AgentInterface.class,
            ListTaskPushNotificationConfigsParams.class,
            ListTaskPushNotificationConfigsResult.class,
            ListTasksParams.class,
            Message.class,
            MessageSendConfiguration.class,
            MessageSendParams.class,
            MethodNotFoundError.class,
            MutualTLSSecurityScheme.class,
            OAuth2SecurityScheme.class,
            OAuthFlows.class,
            OpenIdConnectSecurityScheme.class,
            Part.class,
            PushNotificationNotSupportedError.class,
            SecurityRequirement.class,
            SecurityScheme.class,
            StreamingEventKind.class,
            Task.class,
            TaskArtifactUpdateEvent.class,
            TaskIdParams.class,
            TaskNotCancelableError.class,
            TaskNotFoundError.class,
            TaskPushNotificationConfig.class,
            TaskQueryParams.class,
            TaskState.class,
            TaskStatus.class,
            TaskStatusUpdateEvent.class,
            TextPart.class,
            TransportProtocol.class,
            UnsupportedOperationError.class,
            UpdateEvent.class,
            VersionNotSupportedError.class,
            CollectionCopies.class,
            ErrorDetail.class,
            PageToken.class,
            Utils.class
})
public final class A2AReflectionConfig {
    private A2AReflectionConfig() {}
}
