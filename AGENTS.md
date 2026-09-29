# ValAgent — AGENTS.md

## מה זה
אפליקציית אנדרואיד: צ'אט סוכן AI בעברית (RTL) שמתחבר לכל ספק OpenAI-compatible
(ברירת מחדל: NVIDIA NIM) ומריץ כלים משרת MCP (Streamable HTTP) — עם הצגת
קריאות כלים ותוצאות בתוך הצ'אט.

## ארכיטקטורה
- **WebView app** — כל ה-UI ב-`app/src/main/assets/index.html` (HTML/CSS/JS,
  טוקנים סמנטיים, מצב כהה דרך `[data-theme]`). אין ממשק native מלבד הגשר.
- **MainActivity.java** — WebView + `AndroidBridge`:
  - `post(callId,url,headers,body,stream)` — HTTP ב-Java (עוקף CORS),
    סטרימינג SSE של ספקי OpenAI (מרכיב `tool_calls` מדלתות לפי `index`),
    מחזיר headers (לכידת `mcp-session-id`).
  - `cancel/toast/openUrl/theme` — עזרים.
  - כל התקשורת חוזרת ל-JS דרך `window.__delta/__done`.
- **לקוח MCP** ב-JS: initialize → notifications/initialized → tools/list →
  tools/call, כולל פענוח תשובות JSON או SSE, סשן ו-Bearer token.
- **לולאת סוכן**: עד 8 סבבים של LLM↔tools; תוצאת כלי נדחסת כ-`role:"tool"`.
- מקור רשת יחיד: `HttpURLConnection` (אין תלות חיצונית ב-Java).
- הגדרות נשמרות ב-localStorage (`valagent.settings.v1`).

## קבצים עיקריים
- `app/src/main/assets/index.html` — UI + לוגיקה (צ'אט, הגדרות, MCP, לולאה)
- `app/src/main/java/com/zyvo/valagent/MainActivity.java` — גשר רשת
- `.github/workflows/build.yml` — בנייה בענן (gradle assembleDebug + release)

## פקודות
- בנייה רק בענן: push ל-main → GitHub Actions → APK ב-Releases
  (`apk-<run_number>`). אסור להריץ gradle בטלפון.
- שינוי: ערוך קבצים → commit → push → APK חדש אוטומטית.

## החלטות (רשומות)
- 2026-09-29: v1.0 נבנתה במצב אוטופיילוט לפי בקשת המשתמש (ללא שאלות;
  עיצוב נבחר ע"י zyvo: "קונסולת סוכן", טורקיז #0F766E/#14B8A6).
- נעשה שימוש ב-`usesCleartextTraffic=true` כדי לאפשר שרתי MCP מקומיים (http).
- סטרימינג מופעל בבקשות צ'אט; בקשות MCP ללא stream אך SSE מפוענח בכל מקרה.

## מצב נוכחי
- v1.0: צ'אט עברי, הגדרות (API/mcp/token/ערכת עיצוב), MCP עם בדיקת חיבור,
  כרטיסי כלים מתקפלים, סטרימינג, עצירה, ניקוי צ'אט, מצב כהה, אייקון מסתגל.
