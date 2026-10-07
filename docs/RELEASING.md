# Releasing

The libraries are published to Maven Central under the `dev.mooner` namespace:

| Artifact | Contents |
|---|---|
| `dev.mooner.neonjs:neonjs-core` | the engine |
| `dev.mooner.neonjs:neonjs-intl` | ECMA-402 and the non-ISO Temporal calendars (ICU4J) |
| `dev.mooner.neonjs:neonjs-android` | dex code definer for the JIT and `Java.extend` on Android (dx) |
| `dev.mooner.neonjs:neonjs-android-d8` | D8 converter for `neonjs-android` (depends on `com.android.tools:r8` from Google's Maven repository) |

`neonjs-cli` and `neonjs-test262` are not published. Each artifact comes with a sources jar, a javadoc jar (Dokka
HTML), Gradle module metadata and GPG signatures; the build uses
[gradle-maven-publish-plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) and the Central
Portal (OSSRH is shut down). The version comes from `gradle.properties` (`0.1.0-SNAPSHOT` between releases); a release
passes its own with `-Pversion=`.

## One-time setup

1. **Central Portal token.** Sign in to [central.sonatype.com](https://central.sonatype.com) with the account that owns
   the `dev.mooner` namespace, then *View Account → Generate User Token*. The token's username and password are the
   publishing credentials (the account password does not work).
2. **Signing key.** Publications are signed with a GPG key whose public part is on a key server that Central checks
   (`keyserver.ubuntu.com`, `keys.openpgp.org`). To check an existing key:
   `curl "https://keyserver.ubuntu.com/pks/lookup?op=index&search=0x<KEY ID>"`. To upload one:
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY ID>`.
3. **GitHub secrets** (repository *Settings → Secrets and variables → Actions*), used by
   [`.github/workflows/release.yml`](../.github/workflows/release.yml):

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | token username |
   | `MAVEN_CENTRAL_PASSWORD` | token password |
   | `SIGNING_KEY` | `gpg --armor --export-secret-keys <KEY ID>`, the whole block |
   | `SIGNING_KEY_PASSWORD` | the key's passphrase |

## Releasing a version

1. Make sure CI is green on the commit to release.
2. Tag it and push the tag:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
   The *Release* workflow builds, runs the unit tests and uploads the deployment to the Central Portal, which
   validates it (signatures, POM, sources and javadoc jars).
3. Open [Deployments](https://central.sonatype.com/publishing/deployments), check the files and press *Publish*.
   The artifacts appear on Maven Central after a few minutes (search results take longer).

To skip the manual step once the process is trusted, use `publishToMavenCentral(automaticRelease = true)` in the
root `build.gradle.kts`.

## Publishing from a workstation

Put the same values in `~/.gradle/gradle.properties` (never in the repository):

```properties
mavenCentralUsername=…
mavenCentralPassword=…
signingInMemoryKey=…        # the armored key on one line, newlines replaced by \n
signingInMemoryKeyPassword=…
```

then run `./gradlew publishToMavenCentral -Pversion=0.1.0 --no-configuration-cache` and publish the deployment as
above.

## Checking the artifacts

With the snapshot version of `gradle.properties`, `./gradlew publishToMavenLocal` writes all the artifacts to
`~/.m2/repository/dev/mooner/neonjs/` without credentials (snapshots are not signed; a release version needs the
signing key even for a local publication). CI runs it on every push. Inspect the POMs there (license, developers,
SCM, dependencies) before a first release.

Snapshots can be published too (`./gradlew publishToMavenCentral` with a `-SNAPSHOT` version) once snapshots are
enabled for the namespace in the Central Portal; consumers then add
`maven("https://central.sonatype.com/repository/maven-snapshots/")`.
