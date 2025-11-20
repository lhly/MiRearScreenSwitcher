import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show SynchronousFuture;
import 'app_strings.dart';

class AppLocalizations {
  final Locale locale;

  AppLocalizations(this.locale);

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  String get languageCode => locale.languageCode;
  String? get countryCode => locale.countryCode;

  String get fullLocale {
    if (countryCode != null && countryCode!.isNotEmpty) {
      return '${languageCode}_$countryCode';
    }
    return languageCode;
  }

  String translate(String key) {
    // Try full locale first (e.g. zh_CN)
    String result = AppStrings.get(key, fullLocale);

    // If result is the key itself (meaning not found or fallback to en happened inside AppStrings but we want to be sure),
    // we can try just language code if needed, but AppStrings logic handles fallback to EN.
    // However, for zh_CN vs zh_TW, we need to be careful.

    // Special handling for Chinese
    if (languageCode == 'zh') {
      if (countryCode == 'TW' || countryCode == 'HK') {
        return AppStrings.get(key, 'zh_TW');
      } else {
        return AppStrings.get(key, 'zh_CN');
      }
    }

    return AppStrings.get(key, languageCode);
  }
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  bool isSupported(Locale locale) =>
      ['en', 'zh', 'fr', 'de', 'es', 'ja', 'ko'].contains(locale.languageCode);

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(AppLocalizations(locale));
  }

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}
