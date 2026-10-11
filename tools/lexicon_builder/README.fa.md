# ابزار ساخت بستهٔ لغت SubLearn

این ابزار دیتابیس `fastdic_plain.sqlite` را به فرمت v2 خود SubLearn
(`sublearn-lexicon-en-fa-v2.sqlite`) تبدیل می‌کند.

## پیش‌نیازها

- **Python 3.10** یا بالاتر (روی ویندوز از [python.org](https://python.org) نصب کنید)
- فایل `fastdic_plain.sqlite` (دیتابیس اصلی لغت‌نامه)

## استفادهٔ سریع

### ویندوز
۱. روی `run_build_lexicon.bat` دبل‌کلیک کنید.
۲. مسیر فایل `fastdic_plain.sqlite` را وارد کنید.
۳. صبر کنید تا تمام شود.

### مک / لینوکس
```bash
chmod +x run_build_lexicon.sh
./run_build_lexicon.sh
```
مسیر فایل را وارد کنید و صبر کنید.

### خط فرمان (هر سیستم‌عاملی)
```bash
python3 build_lexicon.py --input /path/to/fastdic_plain.sqlite --output ./output
```

## خروجی‌ها

در پوشهٔ `output/` دو فایل ساخته می‌شود:

| فایل | توضیح |
| --- | --- |
| `sublearn-lexicon-en-fa-v2.sqlite` | بستهٔ لغت v2 آمادهٔ ورود به برنامه |
| `build_report.json` | گزارش کامل: تعداد سطرها، SHA-256، نمونه‌های حذف‌شده |

## مرحلهٔ بعد: ورود به برنامه

۱. فایل `sublearn-lexicon-en-fa-v2.sqlite` را به گوشی منتقل کنید.
۲. در SubLearn به **تنظیمات ← یادگیری هوشمند ← دیکشنری** بروید.
۳. **افزودن از فایل** را بزنید و فایل را انتخاب کنید.
۴. صبر کنید تا import تمام شود.

## ساختار v2

| جدول | توضیح |
| --- | --- |
| `meta` | فراداده (نسخه، زبان، تاریخ ساخت) |
| `lemma_form` | فرم‌های صرف‌شده ← ریشه (`went → go`) |
| `entry` | مدخل اصلی (کلمه، عبارت، نوع، سطح CEFR) |
| `sense` | معانی هر مدخل (فارسی، انگلیسی، حوزه) |
| `example` | جمله‌های نمونه (انگلیسی + فارسی) |
| `pattern` | الگوی تطبیق برای عبارات |
| `relation` | مترادف، متضاد، خانوادهٔ کلمه |

## قوانین حذف و دسته‌بندی

- **idiom معتبر**: ۲ تا ۸ توکن، دارای معنی فارسی، دارای ساختار قابل‌الگو
- اگر کلید یک phrasal verb موجود باشد و عبارت بلندتر از آن شروع شود، به‌عنوان **مثال** یا **collocation** ثبت می‌شود
- غیر از این‌ها حذف و در `build_report.json` شمرده می‌شود

## تست با نمونهٔ کوچک

برای تست بدون دیتابیس کامل:
```bash
python3 build_lexicon.py --input /path/to/dictionaryproject/docs/sample.sqlite --output ./output
```

## فایل‌های این پوشه

| فایل | توضیح |
| --- | --- |
| `build_lexicon.py` | اسکریپت اصلی تبدیل |
| `run_build_lexicon.bat` | اجرای یک‌کلیکی ویندوز |
| `run_build_lexicon.sh` | اجرای یک‌کلیکی مک/لینوکس |
| `README.fa.md` | همین راهنما |
| `output/` | خروجی‌ها (ساخته نمی‌شود مگر اجرا کنید) |

## خطاهای رایج

- **`File not found`**: مسیر فایل را درست وارد کنید. اگر فاصله دارد، داخل نقل‌قول بگذارید.
- **`Python not found`**: Python 3.10+ نصب کنید و به PATH اضافه کنید.
- **خروجی خالی**: دیتابیس ورودی ممکن است ساختار متفاوتی داشته باشد. از `sample.sqlite` ریپوی `dictionaryproject` استفاده کنید.
