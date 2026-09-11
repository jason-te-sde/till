# Releasing

Rare enough to be written down rather than remembered.

## Before

```bash
scripts/preflight.sh
mvn -B -ntp test -pl till-testkit -Dtill.sim.seeds=10000 -Dtest=SoakTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

The soak is not part of `verify` and is the thing most likely to find something. Do not skip it for a
release.

Check that the numbers in `README.md` still describe the build. Coverage, test count, and the soak
figures are all measured, and a README quoting last month's numbers is a README nobody trusts.

## The version

The project is at `0.x`. The rule until `1.0`:

- **Minor** — anything that changes a wire format, a schema, or the `Ledger` contract.
- **Patch** — everything else.

After `1.0` it is semantic versioning, with `Ledger`, `Kernel`, `Command`, `Outcome` and the HTTP API
as the public surface. `till-testkit` is explicitly not: it is a testing tool and it will change.

## Steps

1. Update `CHANGELOG.md`: move the unreleased section under the new version with today's date.
2. Set the version everywhere:
   ```bash
   mvn -B versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
   ```
   This updates the parent and every module. The release workflow asserts that the tag matches, so a
   mismatch fails the release rather than producing artifacts nobody can match to a tag.
3. Commit, tag, push:
   ```bash
   git commit -am "Release 0.2.0"
   git tag -a v0.2.0 -m "0.2.0"
   git push && git push --tags
   ```
4. The **Release** workflow runs the full suite against PostgreSQL, builds with `-Prelease`, and
   opens a **draft** release with `till-server.jar` and `till-client-*-cli.jar` attached. Read the
   generated notes, edit them, publish.
5. Publishing the release triggers **Publish**, which signs the artifacts and uploads them to Maven
   Central as a *staged* deployment.
6. Log in to the Central portal and release the staging repository by hand.

Step 6 is manual on purpose. A version on Central can never be replaced, only superseded, so it is
looked at by a person before it becomes permanent.

## After

```bash
mvn -B versions:set -DnewVersion=0.3.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Back to snapshots"
```

## If Central is not set up yet

It is not. The account and the signing key cannot live in the repository, and until a maintainer
supplies them the **Publish** workflow does nothing. [`SETUP-PUBLISHING.md`](SETUP-PUBLISHING.md) is
what they follow. Until then `mvn install` puts the modules in a local repository, and every release
has the runnable jars attached to it.
