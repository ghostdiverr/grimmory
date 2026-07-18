import {Component, DestroyRef, computed, effect, inject, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {InputText} from 'primeng/inputtext';
import {ToggleSwitch} from 'primeng/toggleswitch';
import {Button} from 'primeng/button';
import {Toast} from 'primeng/toast';
import {MessageService} from 'primeng/api';
import {ExternalDocLinkComponent} from '../../../../../shared/components/external-doc-link/external-doc-link.component';
import {UserService} from '../../../user-management/user.service';
import {AppSettingsService} from '../../../../../shared/service/app-settings.service';
import {AppSettingKey, AppSettings} from '../../../../../shared/model/app-settings.model';
import {ProwlarrSettingsService} from './prowlarr-settings.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';

@Component({
  standalone: true,
  selector: 'app-prowlarr-settings-component',
  imports: [
    FormsModule,
    InputText,
    ToggleSwitch,
    Button,
    Toast,
    ExternalDocLinkComponent,
    TranslocoDirective
  ],
  providers: [MessageService],
  templateUrl: './prowlarr-settings-component.html',
  styleUrls: ['./prowlarr-settings-component.scss']
})
export class ProwlarrSettingsComponent {
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly prowlarrSettingsService = inject(ProwlarrSettingsService);
  private readonly userService = inject(UserService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  readonly hasPermission = computed(() => {
    const permissions = this.userService.currentUser()?.permissions;
    return !!(permissions?.admin || permissions?.canManageMetadataConfig);
  });

  enabled = signal(false);
  baseUrl = signal('');
  apiKey = signal('');
  showApiKey = signal(false);
  completedDownloadsFolder = signal('');
  bookSubfolder = signal('');
  audiobookSubfolder = signal('');
  bookCategoriesText = signal('');
  audiobookCategoriesText = signal('');
  advancedExpanded = signal(false);
  testingConnection = signal(false);

  private readonly syncSettingsEffect = effect(() => {
    const settings = this.appSettingsService.appSettings();
    if (settings) {
      this.applySettings(settings);
    }
  });

  private applySettings(settings: AppSettings): void {
    const prowlarrSettings = settings.prowlarrSettings;
    this.enabled.set(prowlarrSettings?.enabled ?? false);
    this.baseUrl.set(prowlarrSettings?.baseUrl ?? '');
    this.apiKey.set(prowlarrSettings?.apiKey ?? '');
    this.completedDownloadsFolder.set(prowlarrSettings?.completedDownloadsFolder ?? '');
    this.bookSubfolder.set(prowlarrSettings?.bookSubfolder ?? '');
    this.audiobookSubfolder.set(prowlarrSettings?.audiobookSubfolder ?? '');
    this.bookCategoriesText.set((prowlarrSettings?.bookCategories ?? []).join(', '));
    this.audiobookCategoriesText.set((prowlarrSettings?.audiobookCategories ?? []).join(', '));
  }

  toggleShowApiKey(): void {
    this.showApiKey.update(showApiKey => !showApiKey);
  }

  toggleAdvanced(): void {
    this.advancedExpanded.update(expanded => !expanded);
  }

  private parseCategories(text: string): number[] {
    return text
      .split(',')
      .map(part => part.trim())
      .filter(part => part.length > 0)
      .map(part => Number(part))
      .filter(num => Number.isFinite(num));
  }

  saveSettings(): void {
    const payload = [
      {
        key: AppSettingKey.PROWLARR_SETTINGS,
        newValue: {
          enabled: this.enabled(),
          baseUrl: this.baseUrl().trim(),
          apiKey: this.apiKey().trim(),
          bookCategories: this.parseCategories(this.bookCategoriesText()),
          audiobookCategories: this.parseCategories(this.audiobookCategoriesText()),
          completedDownloadsFolder: this.completedDownloadsFolder().trim(),
          bookSubfolder: this.bookSubfolder().trim(),
          audiobookSubfolder: this.audiobookSubfolder().trim()
        }
      }
    ];

    this.appSettingsService.saveSettings(payload).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: () =>
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsDevice.prowlarr.saveSuccess')
        }),
      error: () =>
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.t.translate('settingsDevice.prowlarr.saveError')
        })
    });
  }

  testConnection(): void {
    this.testingConnection.set(true);
    this.prowlarrSettingsService.testConnection().pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: result => {
        this.testingConnection.set(false);
        this.messageService.add({
          severity: result.success ? 'success' : 'error',
          summary: result.success
            ? this.t.translate('settingsDevice.prowlarr.testConnectionSuccess')
            : this.t.translate('settingsDevice.prowlarr.testConnectionFailure'),
          detail: result.message
        });
      },
      error: () => {
        this.testingConnection.set(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('settingsDevice.prowlarr.testConnectionFailure'),
          detail: this.t.translate('settingsDevice.prowlarr.testConnectionError')
        });
      }
    });
  }
}
