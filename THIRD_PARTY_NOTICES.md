# Third-party notices

This repository is distributed under the MIT License. Dependency versions and
licenses are resolved by Gradle and remain subject to each upstream artifact's
terms.

- Kotlin and Kotlin test libraries
- kotlinx-coroutines
- kotlinx-serialization
- Ktor route/test libraries
- `halo-engine` composite-build modules

The physical Halo implementation uses protocol and transport abstractions from the
sibling `halo-engine` repository. No Brilliant SDK or firmware repository is
vendored here. Any separately obtained SDK, firmware, or device binary must retain
its upstream notices.

Before publishing a binary distribution, generate a notice bundle from the resolved
Gradle artifacts and include any required Apache, MIT, or other notices.
