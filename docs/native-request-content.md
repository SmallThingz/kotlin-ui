# Native request content

`NativeRequestContent` renders the title and body of a host request using native typography. Pass an unparented footer containing the host actions. An optional header can show trusted source identity and dismiss controls. The request body scrolls independently of the footer, and the footer can scroll if a short window cannot fit its actions.

```kotlin
val content = NativeRequestContent(
    context, colors, requestTitle, requestBody,
    footer = approvalActions,
    header = trustedSourceHeader,
    hint = replyHint,
    appearance = appearance,
    dispose = { actionBindings.close() },
)
```

Mount `content.view` within the host's existing window or themed surface. The host owns window insets, action labels and semantics, request lifecycle, and authorization. Title and body are literal text with limits of 120 and 4,000 Unicode scalar values. The header and hint are host-owned content.

Call `close()` on the main thread when the host retires this presentation. It detaches the slots and calls the supplied cleanup once. Cleanup should close the host's button input bindings without producing a user response. The adapter has no request IDs, tokens, transport, or stored values.
