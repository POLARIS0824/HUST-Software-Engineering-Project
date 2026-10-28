import { themeFromSourceColor, argbFromHex, SchemeTonalSpot, Hct } from '@material/material-color-utilities';
import { writeFileSync } from 'node:fs';
const seed = argbFromHex('#E3AF39');
const scheme = new SchemeTonalSpot(Hct.fromInt(seed), false, 0);
const roles = ['primary','onPrimary','primaryContainer','onPrimaryContainer','secondary','onSecondary','secondaryContainer','onSecondaryContainer','tertiary','onTertiary','tertiaryContainer','onTertiaryContainer','background','onBackground','surface','onSurface','surfaceVariant','onSurfaceVariant','outline','outlineVariant','inverseSurface','inverseOnSurface','inversePrimary','surfaceContainerLowest','surfaceContainerLow','surfaceContainer','surfaceContainerHigh','surfaceContainerHighest'];
const values = roles.map(key => `    ${key} = Color(0x${(scheme[key] >>> 0).toString(16).toUpperCase()}),`);
writeFileSync(new URL('./android/app/src/main/kotlin/edu/hust/justmd/prototype/PaperPalette.kt', import.meta.url), 'package edu.hust.justmd.prototype\n\nimport androidx.compose.material3.lightColorScheme\nimport androidx.compose.ui.graphics.Color\n\nval paperColors = lightColorScheme(\n' + values.join('\n') + '\n)\n');
writeFileSync(new URL('./paper-palette.json', import.meta.url), JSON.stringify({source:'#E3AF39',generator:'@material/material-color-utilities SchemeTonalSpot',roles:Object.fromEntries(roles.map(k=>[k,scheme[k]>>>0]))},null,2));
