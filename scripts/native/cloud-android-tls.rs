// Appended only to the exported upstream WebSocket provider.
#[cfg(target_os = "android")]
fn android_tls_connector(endpoint: &Url) -> Result<Option<tokio_tungstenite::Connector>, LinkError> {
    use rustls_platform_verifier::BuilderVerifierExt;
    if endpoint.scheme() != "wss" { return Ok(None); }
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .map_err(|_| LinkError::Transport("Cloud TLS protocol configuration failed".into()))?
        .with_platform_verifier()
        .map_err(|_| LinkError::Transport("Cloud Android certificate verifier unavailable".into()))?
        .with_no_client_auth();
    Ok(Some(tokio_tungstenite::Connector::Rustls(Arc::new(config))))
}
