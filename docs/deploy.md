# Deployment

Pushes to `main` run `.github/workflows/deploy.yml`. The workflow uses GitHub OIDC and does not use static AWS keys.

Role: `arn:aws:iam::664759038511:role/GitHubActionsPersonalWebProdDeploy` in `us-east-1`.

Both AWS jobs select the `production` GitHub environment, so the OIDC subject is `repo:SnapPetal/personal-web:environment:production`. The role trust in `personal-site-stack` must allow that subject. If it instead trusts `repo:SnapPetal/personal-web:ref:refs/heads/main`, remove `environment: production` from the jobs.

## Static site

The `publish-static` job:

1. Collects `static-site/`, `images/profile.png`, `images/favicon.svg`, and the Godot web export.
2. Places the Godot export under `tankgame/`, so the site serves it at `/tankgame/index.html`.
3. Runs `aws s3 sync --delete` to `s3://personal-site-thonbecker`.
4. Sets `font/woff2` and `public,max-age=31536000,immutable` on `fonts/*.woff2`. The rest of the site stays at `public,max-age=300,must-revalidate`.
5. Invalidates CloudFront distribution `EIGIJWMOZIYVW` (`d1l03uefskyk66.cloudfront.net`) for `/*`.

The homepage experience count, verse fragment, and dad-joke player call `https://app.thonbecker.biz`. The Godot client uses the page host on `app.thonbecker.biz` and localhost, and `wss://app.thonbecker.biz/tankgame-ws` from the static site.

`--delete` removes objects in the web bucket that are not part of this site.

## Application jar

The `release` job uses Temurin 25, installs the design-system WebJar, and runs `mvn -B package`. That command runs the test phase. Recent Pull Request runs of `mvn -B verify` succeed on GitHub-hosted runners, so the release does not skip tests.

Maven already sets `java.version` and `maven.compiler.release` to 25. The job copies `target/personal-1.0.0.jar` to these release-bucket keys, checksum last:

- `s3://personal-site-releases-thonbecker/personal-web.jar`
- `s3://personal-site-releases-thonbecker/personal-web.sha256`

`personal-web.sha256` is the output of `sha256sum personal-web.jar` (`<hash>  personal-web.jar`). The Lightsail instance `personal-web` has a systemd timer that pulls those two objects, checks the jar, keeps the previous jar for manual rollback, and restarts the service.

The jar still contains the Godot web export at `/tankgame/` so `https://app.thonbecker.biz/tankgame` keeps working.

## Domain split

- `thonbecker.biz` and `www.thonbecker.biz` serve the CloudFront site from `personal-site-thonbecker`.
- `booking.thonbecker.biz` is the public booking site.
- `app.thonbecker.biz` is the Spring Boot origin, including WebSockets. The private Cloudflare OS booking integration uses this host and forwards the Cloudflare Access JWT.
- `e.thonbecker.biz` is the PostHog managed-proxy host. It is not served by this workflow.

`docker-compose.yml` remains the local development Postgres definition. It is not used in production.

## Removed container and SSH rollout

This workflow no longer builds a Paketo image, pushes to ECR Public, or rolls a container out over SSH. These files were removed with that path:

- `Aptfile` (buildpack apt packages for the image build)
- `deploy/lightsail/nginx-domains.conf.example`
- `scripts/deploy-lightsail-personalweb.sh`
- `scripts/deploy-personalweb.sh`
- `scripts/deploy-lightsail-static.sh`
- Spring Boot Maven plugin `spring-boot.build-image` configuration in `pom.xml`

The previous Lightsail host is no longer updated by this repository. It keeps running the last container image until that host is stopped.
