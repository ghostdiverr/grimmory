package org.booklore.task.tasks;

import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.acquisition.WantedBookService;
import org.booklore.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WantedListScanTaskTest {

    @Mock
    private WantedBookService wantedBookService;

    @InjectMocks
    private WantedListScanTask task;

    private BookLoreUser user;
    private TaskCreateRequest request;

    @BeforeEach
    void setUp() {
        user = BookLoreUser.builder()
                .permissions(new BookLoreUser.UserPermissions())
                .build();
        request = new TaskCreateRequest();
    }

    @Test
    void validatePermissions_shouldThrowException_whenUserCannotAccessTaskManager() {
        user.getPermissions().setCanAccessTaskManager(false);
        assertThrows(APIException.class, () -> task.validatePermissions(user, request));
    }

    @Test
    void validatePermissions_shouldPass_whenUserCanAccessTaskManager() {
        user.getPermissions().setCanAccessTaskManager(true);
        assertDoesNotThrow(() -> task.validatePermissions(user, request));
    }

    @Test
    void execute_runsScanCycleAndReturnsCompleted() {
        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskType.WANTED_LIST_SCAN, response.getTaskType());
        assertEquals(TaskStatus.COMPLETED, response.getStatus());
        verify(wantedBookService).runScanCycle();
    }

    @Test
    void execute_returnsFailed_whenScanThrows() {
        doThrow(new RuntimeException("scan failed")).when(wantedBookService).runScanCycle();

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskType.WANTED_LIST_SCAN, response.getTaskType());
        assertEquals(TaskStatus.FAILED, response.getStatus());
    }
}
