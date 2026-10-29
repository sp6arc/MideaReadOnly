# Attribution and modifications

This is an independent Kotlin/Android experimental read-only client, not an official Midea application.

Protocol, crypto, cloud request structure and query details were ported or adapted from:

- sususweet/midea_auto_cloud, commit eb1d710243891f8f1eaf4cdd0f3fcd614a8e65d7, Apache License 2.0. See licenses/midea_auto_cloud-Apache-2.0.txt.
- xelhark/midea-cloud-relay, commit 90dafea030ac594a0ef033b1fad27ca7e194d30e, Copyright (c) 2026 Gabriele Platania, MIT. See licenses/midea-cloud-relay-MIT.txt.
- wuwentao/midea-lan, commit 898c12d46256ac3a6006a5e7c58d130caf06945c, MIT. The complete upstream copyright/license text is retained in licenses/midea-lan-MIT.txt.

Changes: Kotlin rewrite of selected read paths; no HA/Docker/LAN stack; no device controls; strict request and hostname allowlists; memory-only session; bounded network reads; sanitized user-initiated export; model-gated/raw telemetry; tests and Polish UI/documentation. Upstream source is not distributed wholesale. New project code is provided under Apache-2.0; the MIT notices for adapted portions remain applicable.

The APK includes the Kotlin standard library 2.0.21 (JetBrains, Apache-2.0). Build-time libraries (compiler, JUnit, Hamcrest, JSON-java and related dependencies) are fetched from Maven Central by the optional build script and are not bundled into the source ZIP. JUnit and JSON-java are not included in the APK; JSON on Android is provided by the platform.
