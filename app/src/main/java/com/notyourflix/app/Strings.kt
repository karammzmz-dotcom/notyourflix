package com.notyourflix.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalLang = staticCompositionLocalOf { "en" }

object Strings {
    private val en = mapOf(
        "home" to "Home", "search" to "Search", "settings" to "Settings",
        "continue_watching" to "Continue Watching", "for_you" to "For You",
        "who" to "Who is watching?", "profiles" to "Profiles", "add_profile" to "Add Profile",
        "change_font" to "Change font", "change_profile" to "Change profile",
        "change_image" to "Change profile image", "change_name" to "Change profile name",
        "change_lang" to "Change language", "upload_movie" to "Upload a movie",
        "lock_add" to "Add profile lock to this profile", "lock_remove" to "Remove profile lock",
        "new_profile" to "Make a new profile", "change_sound" to "Change sound FX",
        "change_intro" to "Change intro", "change_intro_profile" to "Change intro for going in a profile",
        "delete_profile" to "Delete this profile", "movie" to "Movie", "series" to "Series",
        "title" to "Title", "description" to "Description", "cover_image" to "Cover image",
        "upload_own_image" to "Upload your own image", "video_file" to "Upload video: MP4 or WebM",
        "upload" to "Upload", "finish" to "Finish", "play" to "Play", "resume" to "Resume",
        "restart" to "Start over", "delete" to "Delete", "cancel" to "Cancel", "save" to "Save",
        "enter_pin" to "Enter PIN", "set_pin" to "Choose a 4-digit PIN", "confirm_pin" to "Confirm your PIN",
        "wrong_pin" to "Wrong PIN", "pin_mismatch" to "PINs don't match",
        "empty_title" to "Nothing here yet", "empty_sub" to "Upload your first movie",
        "no_results" to "No results", "name" to "Name", "color" to "Color",
        "gallery" to "Choose from gallery", "default_face" to "Use default face",
        "off" to "Off", "custom" to "Custom file…", "soft" to "Soft", "retro" to "Retro", "chime" to "Chime",
        "none" to "None", "zoom" to "Zoom", "glow" to "Glow",
        "font_default" to "Default", "font_sans" to "Sans Serif", "font_serif" to "Serif",
        "font_mono" to "Monospace", "font_hand" to "Handwriting",
        "episodes" to "episodes", "episode" to "Episode", "search_hint" to "Search movies and series",
        "selected" to "Selected", "fill_form" to "Add a title and choose a video", "saved" to "Saved",
        "delete_confirm" to "Delete this title?", "delete_profile_confirm" to "Delete this profile?",
        "last_profile" to "You need at least one profile", "video_type" to "Video type",
        "language" to "Language", "next_episode" to "Next episode", "working" to "Saving…"
    )
    private val ar = mapOf(
        "home" to "الرئيسية", "search" to "بحث", "settings" to "الإعدادات",
        "continue_watching" to "متابعة المشاهدة", "for_you" to "لك",
        "who" to "من يشاهد؟", "profiles" to "الملفات الشخصية", "add_profile" to "إضافة ملف شخصي",
        "change_font" to "تغيير الخط", "change_profile" to "تغيير الملف الشخصي",
        "change_image" to "تغيير صورة الملف الشخصي", "change_name" to "تغيير اسم الملف الشخصي",
        "change_lang" to "تغيير اللغة", "upload_movie" to "رفع فيلم",
        "lock_add" to "إضافة قفل لهذا الملف الشخصي", "lock_remove" to "إزالة قفل الملف الشخصي",
        "new_profile" to "إنشاء ملف شخصي جديد", "change_sound" to "تغيير المؤثرات الصوتية",
        "change_intro" to "تغيير المقدمة", "change_intro_profile" to "تغيير المقدمة عند الدخول إلى ملف شخصي",
        "delete_profile" to "حذف هذا الملف الشخصي", "movie" to "فيلم", "series" to "مسلسل",
        "title" to "العنوان", "description" to "الوصف", "cover_image" to "صورة الغلاف",
        "upload_own_image" to "ارفع صورتك الخاصة", "video_file" to "رفع الفيديو: MP4 أو WebM",
        "upload" to "رفع", "finish" to "إنهاء", "play" to "تشغيل", "resume" to "متابعة",
        "restart" to "البدء من جديد", "delete" to "حذف", "cancel" to "إلغاء", "save" to "حفظ",
        "enter_pin" to "أدخل الرمز السري", "set_pin" to "اختر رمزاً من 4 أرقام", "confirm_pin" to "أكّد الرمز السري",
        "wrong_pin" to "الرمز غير صحيح", "pin_mismatch" to "الرمزان غير متطابقين",
        "empty_title" to "لا يوجد شيء هنا بعد", "empty_sub" to "ارفع أول فيلم لك",
        "no_results" to "لا توجد نتائج", "name" to "الاسم", "color" to "اللون",
        "gallery" to "اختيار من المعرض", "default_face" to "استخدام الوجه الافتراضي",
        "off" to "إيقاف", "custom" to "ملف مخصص…", "soft" to "ناعم", "retro" to "كلاسيكي", "chime" to "جرس",
        "none" to "بدون", "zoom" to "تكبير", "glow" to "توهج",
        "font_default" to "الافتراضي", "font_sans" to "بدون زوائد", "font_serif" to "بزوائد",
        "font_mono" to "أحادي المسافة", "font_hand" to "خط يدوي",
        "episodes" to "حلقات", "episode" to "الحلقة", "search_hint" to "ابحث عن فيلم أو مسلسل",
        "selected" to "تم الاختيار", "fill_form" to "أدخل العنوان واختر فيديو", "saved" to "تم الحفظ",
        "delete_confirm" to "حذف هذا العمل؟", "delete_profile_confirm" to "حذف هذا الملف الشخصي؟",
        "last_profile" to "يجب أن يبقى ملف شخصي واحد على الأقل", "video_type" to "نوع الفيديو",
        "language" to "اللغة", "next_episode" to "الحلقة التالية", "working" to "جارٍ الحفظ…"
    )
    fun get(lang: String, k: String): String = (if (lang == "ar") ar else en)[k] ?: en[k] ?: k
}

@Composable
fun tr(k: String): String = Strings.get(LocalLang.current, k)
