# Releasing

The libraries are published as `dev.mooner.neonjs:<module>`:

| Artifact | Contents |
|---|---|
| `dev.mooner.neonjs:neonjs-core` | the engine |
| `dev.mooner.neonjs:neonjs-intl` | ECMA-402 and the non-ISO Temporal calendars (ICU4J) |
| `dev.mooner.neonjs:neonjs-net` | `fetch` and `WebSocket` (OkHttp), within a network policy |
| `dev.mooner.neonjs:neonjs-node` | Node.js APIs (`node:` modules, `require`, `Buffer`) |
| `dev.mooner.neonjs:neonjs-android` | dex code definer for the JIT and `Java.extend` on Android (dx) |
| `dev.mooner.neonjs:neonjs-android-d8` | D8 converter for `neonjs-android` (depends on `com.android.tools:r8` from Google's Maven repository) |

`neonjs-cli` and `neonjs-test262` are not published. Each artifact comes with a sources jar, an empty javadoc jar,
Gradle module metadata and GPG signatures; the API docs (Dokka HTML) are published to GitHub Pages instead of the
javadoc jar. The version comes from `gradle.properties` (`0.1.0-SNAPSHOT` between releases); a release passes its own
with `-Pversion=`.

## Where releases go

| Repository | URL | When |
|---|---|---|
| GitHub Pages | `https://mooner1022.github.io/neon-js/maven` | every release |
| Maven Central | `https://repo1.maven.org/maven2` | releases picked by hand |

Maven Central limits each organization's publishing per month (release size, file count and release count, shown
under *Usage Overview* on [central.sonatype.com](https://central.sonatype.com); in October 2026 the limits were
80 MB, 1,000 files and 7 releases). With the empty javadoc jars a release of all six modules is about 4.2 MB (the Dokka
javadoc jars made it about 15 MB). Every release
therefore goes to the Maven repository on GitHub Pages, which has no such quota, and only some (milestones, versions
other projects pin for a long time) also go to Central. Both carry the same files, so a consumer that lists both
repositories gets the same artifacts from either.

The Pages repository is the `maven/` directory of the `gh-pages` branch, a plain Maven repository layout that Gradle
and Maven read over HTTPS without credentials; `api/<module>/` next to it holds the latest API docs.

## One-time setup

1. **Signing key.** Publications are signed with a GPG key whose public part is on a key server that Central checks
   (`keyserver.ubuntu.com`, `keys.openpgp.org`). To check an existing key:
   `curl "https://keyserver.ubuntu.com/pks/lookup?op=index&search=0x<KEY ID>"`. To upload one:
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY ID>`.
2. **Central Portal token** (for Central releases). Sign in to [central.sonatype.com](https://central.sonatype.com)
   with the account that owns the `dev.mooner` namespace, then *View Account → Generate User Token*. The token's
   username and password are the publishing credentials (the account password does not work).
3. **GitHub secrets** (repository *Settings → Secrets and variables → Actions*), used by
   [`.github/workflows/release.yml`](../.github/workflows/release.yml):

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | `gpg --armor --export-secret-keys <KEY ID>`, the whole block, with its line breaks or on one line with `\n` escapes |
   | `SIGNING_KEY_PASSWORD` | the key's passphrase |
   | `MAVEN_CENTRAL_USERNAME` | token username (Central only) |
   | `MAVEN_CENTRAL_PASSWORD` | token password (Central only) |
4. **GitHub Pages.** The first release creates the `gh-pages` branch. Then, under *Settings → Pages*, choose
   *Deploy from a branch*, branch `gh-pages`, folder `/ (root)`. Later releases are served a minute or so after the
   workflow pushes them.

## Releasing a version

1. Make sure CI is green on the commit to release.
2. Tag it and push the tag:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
   The *Release* workflow builds, runs the unit tests, publishes the artifacts into `maven/` of `gh-pages` (merging
   the new version into the `maven-metadata.xml` files already there), replaces the docs in `api/` and pushes the
   branch.
3. To put the version on Maven Central as well, open *Actions → Release → Run workflow*, enter the tag, uncheck
   *Publish to GitHub Pages* (it is already there) and check *Upload to Maven Central*. The workflow uploads the
   deployment to the Central Portal, which validates it (signatures, POM, sources and javadoc jars). Then open
   [Deployments](https://central.sonatype.com/publishing/deployments), check the files and press *Publish*. The
   artifacts appear on Maven Central after a few minutes (search results take longer).

To skip the manual step on Central once the process is trusted, use `publishToMavenCentral(automaticRelease = true)`
in the root `build.gradle.kts`.

## Publishing from a workstation

Put the signing values (and, for Central, the token) in `~/.gradle/gradle.properties` (never in the repository):

```properties
signingInMemoryKey=…        # the armored key on one line, newlines replaced by \n
signingInMemoryKeyPassword=…
mavenCentralUsername=…
mavenCentralPassword=…
```

To the Pages repository, publish into a checkout of `gh-pages` and push it:

```bash
git worktree add pages gh-pages
./gradlew publishAllPublicationsToPagesRepository -Pversion=0.1.0 -PpagesRepo="$PWD/pages/maven" --no-configuration-cache
git -C pages add -A && git -C pages commit -m "release: 0.1.0" && git -C pages push origin gh-pages
```

To Central, run `./gradlew publishToMavenCentral -Pversion=0.1.0 --no-configuration-cache` and publish the
deployment as above.

## Checking the artifacts

With the snapshot version of `gradle.properties`, `./gradlew publishToMavenLocal` writes all the artifacts to
`~/.m2/repository/dev/mooner/neonjs/` without credentials (snapshots are not signed; a release version needs the
signing key even for a local publication). CI runs it on every push. Inspect the POMs there (license, developers,
SCM, dependencies) before a first release. `publishAllPublicationsToPagesRepository` without `-PpagesRepo` writes to
`build/pages-maven/`.

Snapshots can be published too (`./gradlew publishToMavenCentral` with a `-SNAPSHOT` version) once snapshots are
enabled for the namespace in the Central Portal; consumers then add
`maven("https://central.sonatype.com/repository/maven-snapshots/")`.
