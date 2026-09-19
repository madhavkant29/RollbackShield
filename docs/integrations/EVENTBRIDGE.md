# EventBridge connector

`connectors/aws/adapter/EventBridgeDiscoveryAdapter` — capability
`EVENT_DISCOVERY`. Lists event buses into `EVENT_BUS` resources.

EventBridge is the control plane's domain-event transport
(`shared/events/adapter/EventBridgeEventPublisher`, published to
`rollbackshield-events`). Events are versioned and duplicate-tolerant;
consumers must not assume exactly-once.

EventBridge is deliberately **not** a synchronous dependency: nothing on a
mutation or preflight path waits for an event. Delivery is verified
against LocalStack by creating a rule with an SQS target and asserting the
event arrives (`AwsAdapterLocalStackIntegrationTest`).
