# AVIP Solar Installation

(Formerly AVIP Solar Install Check. The repository name and app ID are unchanged, so updates keep working.)

Android app for AVIP Solar (AVIP Business Solutions LLP): one app for four teams, from the
sales visit to the quality-checked installation.

**Download the latest APK:**
https://github.com/avipbizsolutions/AVIPSolarInstallCheck/releases/latest/download/AVIP-Solar-Installation.apk

## Who does what
| Team (Users tab) | Sees | Does |
|---|---|---|
| **Sales** | Own sites | New site: customer, phone, address, Google Map location, PM Surya Ghar details, material list (pre-filled with BOM Master defaults). Sends it to the Manager. Sites marked *Converted* in AVIP Solar Sales arrive here as a Draft. |
| **Manager** (Founder / CEO / Manager) | All sites | Verifies, edits anything, sets dispatch & installation dates, assigns the installation team, approves to Dispatch or sends back. QC: approves the installation or sends it for rework. |
| **Dispatch** (Godown) | Sites approved for dispatch, and sites with balance pending | Serial number + label photo for every panel, inverter and battery; quantity sent per item (less = Balance); vehicle, driver, loading photo. |
| **Installation** | Sites assigned to their team | Serial match at site, quality checklist (OK / Not OK / NA, remark and photo on Not OK), 5 required GPS photos, pending work, team-leader and customer signatures, submit. Sends the Installation Report PDF to the customer on WhatsApp. |

Site status flow: Draft → Submitted for Verification → Approved for Dispatch → Dispatched →
Installation In Progress → Installation Submitted → QC Approved (Closed). Side steps: Sent Back
to Sales, Balance Pending, Rework.

## Login and PINs
One login for all AVIP Solar internal apps (Sales, Installation, Expense). People, PINs and roles
live in the private Google Sheet **AVIP Team Directory (Confidential)**; its own script, **AVIP Auth**
(code in the AVIPSolarExpense repo, `auth-service/`), checks every login.

- The team in this app is the person's **Installation App Role** in the directory: Sales, Manager,
  Dispatch or Installation. A blank role means no access to this app.
- Mobile number + 4-digit PIN. The office types a temporary PIN in the directory; at first login the
  person chooses their own PIN, kept only as a salted scrambled code. To reset a forgotten PIN, type a
  new temporary PIN in the PIN cell.
- Five wrong PINs lock the number for 15 minutes. Status "Disabled" blocks the person in every app.
- Names in **Sales Person** and **Installation Team** on the Sites tab must match the names in the
  directory. The old **Users** tab of this sheet is no longer used.

## Photos
Photos can only be taken with the app's own camera, which is the AVIP GPSCam camera: 4:3
frame, live GPS stamp (mini map with pin, address, Lat/Long, facing direction, date & time),
north arrow, GPS in EXIF. A copy goes to the phone gallery under *Pictures/AVIP Solar Installation*.
Photos upload in the background and wait on the phone when there is no signal. They are saved
in Google Drive under **AVIP Install Check Photos / <Site ID> - <Customer>**; the sheet keeps the links.

## One-time setup (office)
1. Open the Google Sheet **AVIP Solar Install Check – Database** from the account that owns it.
2. Extensions → Apps Script → paste `apps-script/Code.gs` as **Code.gs** and `apps-script/AuthClient.gs`
   as a second file **AuthClient.gs** → Save.
3. Project Settings → Script properties: add **AUTH_URL** (AVIP Auth web app URL) and **AUTH_KEY**
   (AVIP Auth server key). Run **setup** once and allow access.
4. Deploy → New deployment → type **Web app** → Execute as **Me**, Who has access **Anyone** → Deploy.
5. Copy the `/exec` URL into `config.json` in this repo (`"endpoint": "…"`). Phones read it at every launch.

After changing `Code.gs`: Deploy → Manage deployments → Edit → Version: **New version** → Deploy
(the URL stays the same).

### Link from AVIP Solar Sales
When a visit is marked **Converted** with an installation date, the AVIP Site Visits Log script
sends it to this app's web app (`kind: "salesVisit"`, protected by the link key that **setup**
prints in the Apps Script log; put it in that script's `INSTALL_CHECK_KEY`). It arrives as a Draft for that sales
executive, with BOM Master defaults as the material list. The same visit is never copied twice.
The sales executive's name in AVIP Solar Sales must match their name in the Users tab.

## Updates are compulsory
Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest
release. On launch the app checks the latest release; a newer version is downloaded and
installed from the start screen, and the old version does not open. Keep `app/release.keystore`
in the repo: every update must be signed with it.

## Code map
| File | What it does |
|---|---|
| `apps-script/Code.gs`, `AuthClient.gs` | Backend on the Google Sheet: login via AVIP Auth, sites, materials, dispatch, serials, photos to Drive, checklist, sign-off, QC, status log, link from AVIP Solar Sales |
| `Api.kt` | Calls the web app; session; teams |
| `Data.kt` | Sites, statuses, BOM / checklist lists |
| `Uploads.kt` | Background photo & serial upload queue |
| `SplashActivity.kt`, `Updater.kt` | Compulsory update (same as AVIP Solar Sales) |
| `LoginActivity.kt` | Login and first-login PIN |
| `HomeActivity.kt` | Each team's site list and counts |
| `SiteActivity.kt` | One site, next step per team, QC |
| `SiteFormActivity.kt`, `MaterialsActivity.kt` | Sales entry and material list |
| `VerifyActivity.kt` | Manager verification |
| `DispatchActivity.kt` | Godown dispatch |
| `InstallActivity.kt` | Installation checklist, photos, sign-off |
| `CameraActivity.kt`, `Stamp.kt`, `MapTiles.kt`, `Geocoding.kt`, `Compass.kt` | GPS camera, same stamp as AVIP GPSCam |
| `ReportPdf.kt` | Customer Installation Report PDF and WhatsApp share |
