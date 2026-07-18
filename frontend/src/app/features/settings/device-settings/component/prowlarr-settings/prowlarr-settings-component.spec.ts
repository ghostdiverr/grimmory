import {signal, type WritableSignal} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {of, throwError} from 'rxjs';

import {MessageService} from 'primeng/api';

import {getTranslocoModule} from '../../../../../core/testing/transloco-testing';
import {type AppSettings, AppSettingKey} from '../../../../../shared/model/app-settings.model';
import {AppSettingsService} from '../../../../../shared/service/app-settings.service';
import {UserService} from '../../../user-management/user.service';
import {ProwlarrSettingsComponent} from './prowlarr-settings-component';
import {ProwlarrSettingsService, type ProwlarrConnectionTestResult} from './prowlarr-settings.service';

interface MockUser {
  permissions: {
    admin: boolean;
    canManageMetadataConfig: boolean;
  };
}

describe('ProwlarrSettingsComponent', () => {
  let fixture: ComponentFixture<ProwlarrSettingsComponent>;
  let component: ProwlarrSettingsComponent;
  let appSettingsSignal: WritableSignal<AppSettings | null>;
  let currentUserSignal: WritableSignal<MockUser | null>;
  let saveSettings: ReturnType<typeof vi.fn>;
  let testConnection: ReturnType<typeof vi.fn>;
  let messageService: MessageService;
  let messageServiceAdd: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    appSettingsSignal = signal<AppSettings | null>(null);
    currentUserSignal = signal<MockUser | null>({
      permissions: {admin: false, canManageMetadataConfig: true}
    });
    saveSettings = vi.fn(() => of(void 0));
    testConnection = vi.fn(() => of({success: true, message: 'Connected', version: '1.2.3'}));
    // The `<p-toast>` in the component's own template needs a real MessageService instance
    // (it subscribes to its internal `messageObserver`/`clearObserver` subjects), so spy on
    // `add` rather than replacing the service with a bare mock object.
    messageService = new MessageService();
    messageServiceAdd = vi.spyOn(messageService, 'add') as unknown as ReturnType<typeof vi.fn>;

    await TestBed.configureTestingModule({
      imports: [ProwlarrSettingsComponent, getTranslocoModule()],
      providers: [
        {
          provide: AppSettingsService,
          useValue: {
            appSettings: appSettingsSignal,
            saveSettings,
          },
        },
        {
          provide: UserService,
          useValue: {
            currentUser: currentUserSignal,
          },
        },
        {
          provide: ProwlarrSettingsService,
          useValue: {
            testConnection,
          },
        },
        {provide: MessageService, useValue: messageService},
      ],
    })
      // The component declares its own `providers: [MessageService]` (a local toast instance),
      // which otherwise shadows the module-level mock above. Override it directly so the
      // spy is what the component actually injects.
      .overrideComponent(ProwlarrSettingsComponent, {
        set: {
          providers: [{provide: MessageService, useValue: messageService}],
        },
      })
      .compileComponents();

    fixture = TestBed.createComponent(ProwlarrSettingsComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('hydrates form fields from the loaded app settings', () => {
    appSettingsSignal.set(buildSettings({
      enabled: true,
      baseUrl: 'http://prowlarr.local',
      apiKey: 'secret-key',
      bookCategories: [7000, 7020],
      audiobookCategories: [3030],
      completedDownloadsFolder: '/downloads',
      bookSubfolder: 'books',
      audiobookSubfolder: 'audiobooks',
    }));
    fixture.detectChanges();

    expect(component.enabled()).toBe(true);
    expect(component.baseUrl()).toBe('http://prowlarr.local');
    expect(component.apiKey()).toBe('secret-key');
    expect(component.bookCategoriesText()).toBe('7000, 7020');
    expect(component.audiobookCategoriesText()).toBe('3030');
    expect(component.completedDownloadsFolder()).toBe('/downloads');
    expect(component.bookSubfolder()).toBe('books');
    expect(component.audiobookSubfolder()).toBe('audiobooks');
  });

  it('saves settings with trimmed values and parsed category ids', () => {
    fixture.detectChanges();

    component.enabled.set(true);
    component.baseUrl.set(' http://prowlarr.local ');
    component.apiKey.set(' secret-key ');
    component.bookCategoriesText.set('7000, 7020,');
    component.audiobookCategoriesText.set('3030');
    component.completedDownloadsFolder.set(' /downloads ');
    component.bookSubfolder.set(' books ');
    component.audiobookSubfolder.set(' audiobooks ');

    component.saveSettings();

    expect(saveSettings).toHaveBeenCalledTimes(1);
    const payload = saveSettings.mock.calls[0][0] as {key: string; newValue: unknown}[];
    expect(payload[0].key).toBe(AppSettingKey.PROWLARR_SETTINGS);
    expect(payload[0].newValue).toEqual({
      enabled: true,
      baseUrl: 'http://prowlarr.local',
      apiKey: 'secret-key',
      bookCategories: [7000, 7020],
      audiobookCategories: [3030],
      completedDownloadsFolder: '/downloads',
      bookSubfolder: 'books',
      audiobookSubfolder: 'audiobooks',
    });
  });

  it('shows a success toast after a successful save', () => {
    fixture.detectChanges();

    component.saveSettings();

    expect(messageServiceAdd).toHaveBeenCalledWith(
      expect.objectContaining({severity: 'success'})
    );
  });

  it('shows an error toast when saving fails', () => {
    saveSettings.mockReturnValueOnce(throwError(() => new Error('save failed')));
    fixture.detectChanges();

    component.saveSettings();

    expect(messageServiceAdd).toHaveBeenCalledWith(
      expect.objectContaining({severity: 'error'})
    );
  });

  it('renders the access-denied card and hides the form for users without permission', () => {
    currentUserSignal.set({permissions: {admin: false, canManageMetadataConfig: false}});
    fixture.detectChanges();

    expect(component.hasPermission()).toBe(false);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.access-denied-card')).toBeTruthy();
    expect(compiled.querySelector('#prowlarrBaseUrl')).toBeFalsy();
  });

  it('renders the form for admins even without the metadata-config permission', () => {
    currentUserSignal.set({permissions: {admin: true, canManageMetadataConfig: false}});
    fixture.detectChanges();

    expect(component.hasPermission()).toBe(true);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.access-denied-card')).toBeFalsy();
  });

  it('shows a success toast with the response message when the connection test succeeds', () => {
    fixture.detectChanges();

    component.testConnection();

    expect(testConnection).toHaveBeenCalledTimes(1);
    expect(component.testingConnection()).toBe(false);
    expect(messageServiceAdd).toHaveBeenCalledWith(
      expect.objectContaining({severity: 'success', detail: 'Connected'})
    );
  });

  it('shows an error toast with the response message when the connection test reports failure', () => {
    testConnection.mockReturnValueOnce(of({
      success: false,
      message: 'Unable to reach Prowlarr',
      version: null,
    } satisfies ProwlarrConnectionTestResult));
    fixture.detectChanges();

    component.testConnection();

    expect(component.testingConnection()).toBe(false);
    expect(messageServiceAdd).toHaveBeenCalledWith(
      expect.objectContaining({severity: 'error', detail: 'Unable to reach Prowlarr'})
    );
  });

  it('shows an error toast and resets the pending state when the connection test request errors', () => {
    testConnection.mockReturnValueOnce(throwError(() => new Error('network error')));
    fixture.detectChanges();

    component.testConnection();

    expect(component.testingConnection()).toBe(false);
    expect(messageServiceAdd).toHaveBeenCalledWith(
      expect.objectContaining({severity: 'error'})
    );
  });

  function buildSettings(prowlarr: AppSettings['prowlarrSettings']): AppSettings {
    return {
      prowlarrSettings: prowlarr,
    } as AppSettings;
  }
});
