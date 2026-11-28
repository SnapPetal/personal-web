# Google Booking Calendar

The `calendar` module writes each booking to the Google Calendar named **Booking**. This page gives the
steps to set up the calendar and authorization, deploy the change, and test it.

Do these steps before you deploy. If the calendar or the secret does not exist, each booking create and
cancel fails. The failures go to PostHog error tracking, and the event publications stay incomplete.
Spring Modulith replays them when the application restarts.

## 1. Create the calendar

1. Sign in to Google Calendar with the owner account.
2. Create a new calendar. Set its name to `Booking`.
3. Make sure that no other calendar that you can edit has the name `Booking`. The application stops
   with an error when it finds two.

## 2. Create the OAuth client

1. In Google Cloud Console, create a project or select one.
2. Enable the **Google Calendar API**.
3. Configure the OAuth consent screen. Set the user type to **External** and add the owner account.
4. Set the publishing status to **In production**. A refresh token for an app in **Testing** status
   expires after 7 days. The app does not need Google verification for one user. Google shows an
   "unverified app" warning during consent. Accept it.
5. Create an OAuth client ID of type **Desktop app**. Record the client ID and the client secret.

## 3. Get a refresh token

The application uses the scope `https://www.googleapis.com/auth/calendar`. It reads the calendar list
to find `Booking`, and it creates, reads, and deletes events.

1. Open this URL in a browser. Replace `CLIENT_ID`.

   ```
   https://accounts.google.com/o/oauth2/v2/auth?client_id=CLIENT_ID&redirect_uri=http://127.0.0.1:8085&response_type=code&scope=https://www.googleapis.com/auth/calendar&access_type=offline&prompt=consent
   ```
2. Sign in with the owner account and accept.
3. The browser goes to `http://127.0.0.1:8085/?code=...`. The page does not load. This is correct.
   Copy the `code` value from the address bar.
4. Exchange the code for a refresh token:

   ```bash
   curl -s https://oauth2.googleapis.com/token \
     -d client_id=CLIENT_ID \
     -d client_secret=CLIENT_SECRET \
     -d code=CODE \
     -d grant_type=authorization_code \
     -d redirect_uri=http://127.0.0.1:8085
   ```
5. Record the `refresh_token` value from the response.

## 4. Store the secret

Store the three values in AWS Secrets Manager in `us-east-1`:

```bash
aws secretsmanager create-secret \
  --region us-east-1 \
  --name google/booking-calendar-oauth \
  --secret-string '{"client_id":"CLIENT_ID","client_secret":"CLIENT_SECRET","refresh_token":"REFRESH_TOKEN"}'
```

The AWS identity in `PERSONAL_AWS_ACCESS_KEY_ID` must have `secretsmanager:GetSecretValue` on this
secret. If its policy names only `opencloud/calendar-app-tokens`, add the new secret.

## 5. Check the database before deploy

Run these queries on the production database. Record the results.

```sql
-- Publications that the application will replay on the next restart
SELECT listener_id, count(*)
  FROM event_publication
 WHERE completion_date IS NULL
 GROUP BY listener_id;

-- Mappings that point at the retired OpenCloud collection
SELECT count(*) FROM booking.calendar_event_mappings;
```

## 6. Deploy

The deploy does these steps on startup:

1. Liquibase changeset `040-calendar-event-mapping-provider` marks all existing mappings as
   `OPENCLOUD`. Deletion sync never reads these rows, so the empty Google calendar cannot cancel them.
2. Liquibase changeset `040-reopen-opencloud-calendar-publications` reopens the calendar listener's
   `BookingCreatedEvent` publication for each upcoming `PENDING` or `CONFIRMED` booking that has an
   `OPENCLOUD` mapping. The notification publication stays complete, so no attendee gets a second email.
3. Spring Modulith replays all incomplete publications. Each replayed create adds the Google event and
   changes the mapping to `GOOGLE`. Each replayed cancel deletes the Google event if it exists.
4. `CalendarSyncScheduler` finds the `Booking` calendar. If this fails, PostHog gets an `$exception` with
   source `calendar.verify`.

## 7. Test end to end

1. Make a test booking on the booking page.
2. Make sure that the event shows in the `Booking` calendar in less than one minute.
3. Cancel the test booking from its confirmation page or from booking administration.
4. Make sure that the event is removed from the `Booking` calendar.
5. Run the first query in step 5 again. The result must have no rows.
6. In PostHog error tracking, make sure that there is no `$exception` with a source that starts with
   `calendar.`.
7. Make sure that the upcoming bookings from step 5 show in the `Booking` calendar.

## If a step fails

- Do not set `calendar.google.enabled` to `false` in production. The listener then marks publications
  complete without a calendar event, and you cannot replay them.
- Correct the calendar name or the secret, then restart the application. The restart replays the
  incomplete publications.

