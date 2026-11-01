package com.notearray.resource;

import com.notearray.entity.Note;
import com.notearray.entity.TodoItem;
import com.notearray.entity.TodoList;
import com.notearray.entity.User;
import com.notearray.service.SessionService;
import com.notearray.service.TodoChecklistParser;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Standalone TODO lists and their nested items.
 *
 * <p>Every endpoint is owner-scoped: regular users only see their own lists,
 * while superusers may see all (matching {@link NoteResource}). Items are
 * ordered per parent by {@code position}; the API returns a flat list already
 * in tree order so the UI can render nesting without rebuilding it.</p>
 */
@Path("/api/todo-lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TodoListResource {

    private static final Set<String> PRIORITIES = Set.of("NONE", "LOW", "MEDIUM", "HIGH");
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_TEXT_LENGTH = 2000;

    @Context
    HttpHeaders headers;

    @Inject
    SessionService sessions;

    // ------------------------------------------------------------------
    // Request / response shapes
    // ------------------------------------------------------------------

    public record TodoListSummary(UUID id, String title, UUID noteId, String noteTitle,
                                  boolean pinned, long totalCount, long openCount,
                                  long overdueCount, long version, Instant createdAt,
                                  Instant updatedAt) {}

    public record TodoItemView(UUID id, UUID parentId, String text, boolean checked,
                               int position, String priority, LocalDate dueDate,
                               long version, Instant createdAt, Instant updatedAt) {}

    public record TodoListDetail(UUID id, String title, UUID noteId, String noteTitle,
                                 boolean pinned, long version, Instant createdAt,
                                 Instant updatedAt, List<TodoItemView> items) {}

    public record CreateListRequest(String title, UUID noteId) {}

    public record UpdateListRequest(String title, Boolean pinned, Long version) {}

    public record LinkNoteRequest(UUID noteId) {}

    public record CreateItemRequest(String text, UUID parentId, String priority, String dueDate) {}

    public record UpdateItemRequest(String text, Boolean checked, String priority,
                                    String dueDate, Long version) {}

    public record ReorderItem(UUID id, UUID parentId) {}

    public record ReorderRequest(List<ReorderItem> items) {}

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    /** All lists for the signed-in user (optionally only those linked to one note). */
    @GET
    public Response list(@QueryParam("noteId") UUID noteId) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }

        Sort sort = Sort.by("pinned", Sort.Direction.Descending)
                .and("updatedAt", Sort.Direction.Descending);

        List<TodoList> lists;
        if (noteId != null) {
            Note note = Note.findById(noteId);
            if (note == null || !canAccessNote(note, user)) {
                return notFound();
            }
            lists = user.superuser
                    ? TodoList.list("noteId = ?1", sort, noteId)
                    : TodoList.list("ownerId = ?1 and noteId = ?2", sort, user.id, noteId);
        } else {
            lists = user.superuser
                    ? TodoList.listAll(sort)
                    : TodoList.list("ownerId = ?1", sort, user.id);
        }

        Map<UUID, List<TodoItem>> itemsByList = itemsByList(lists);
        Map<UUID, String> noteTitles = noteTitles(lists);
        List<TodoListSummary> summaries = lists.stream()
                .map(list -> toSummary(list, itemsByList.getOrDefault(list.id, List.of()),
                        noteTitleFor(list.noteId, noteTitles)))
                .toList();
        return Response.ok(summaries).build();
    }

    /** One list with all of its items in tree order. */
    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        TodoList list = findAccessibleList(id, user);
        if (list == null) {
            return notFound();
        }
        return Response.ok(toDetail(list, TodoItem.list("listId", list.id))).build();
    }

    @POST
    @Transactional
    public Response create(CreateListRequest request) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        String title = normalizeTitle(request == null ? null : request.title());
        if (title == null) {
            return badRequest("title is required");
        }
        UUID noteId = request == null ? null : request.noteId();
        if (noteId != null) {
            Note note = Note.findById(noteId);
            if (note == null || !canAccessNote(note, user)) {
                return notFound();
            }
        }

        TodoList list = new TodoList();
        list.ownerId = user.id;
        list.title = title;
        list.noteId = noteId;
        list.persist();
        list.flush();
        return Response.status(Response.Status.CREATED)
                .entity(toDetail(list, List.of()))
                .build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") UUID id, UpdateListRequest request) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        TodoList list = findAccessibleList(id, user);
        if (list == null) {
            return notFound();
        }
        if (request == null || request.version() == null) {
            return badRequest("version is required");
        }
        if (request.version() != list.version) {
            return conflict("TODO list was modified; reload and try again");
        }
        if (request.title() != null) {
            String title = normalizeTitle(request.title());
            if (title == null) {
                return badRequest("title is required");
            }
            list.title = title;
        }
        if (request.pinned() != null) {
            list.pinned = request.pinned();
        }
        list.flush();
        return Response.ok(toDetail(list, TodoItem.list("listId", list.id))).build();
    }

    @PUT
    @Path("/{id}/note")
    @Transactional
    public Response linkNote(@PathParam("id") UUID id, LinkNoteRequest request) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        TodoList list = findAccessibleList(id, user);
        if (list == null) {
            return notFound();
        }
        if (request == null) {
            return badRequest("noteId is required (use null to unlink)");
        }
        if (request.noteId() != null) {
            Note note = Note.findById(request.noteId());
            if (note == null || !canAccessNote(note, user)) {
                return notFound();
            }
        }
        list.noteId = request.noteId();
        list.flush();
        return Response.ok(toDetail(list, TodoItem.list("listId", list.id))).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        TodoList list = findAccessibleList(id, user);
        if (list == null) {
            return notFound();
        }
        list.delete();
        return Response.noContent().build();
    }

    /** Create a list from the markdown checkboxes in a note. */
    @POST
    @Path("/from-note/{noteId}")
    @Transactional
    public Response createFromNote(@PathParam("noteId") UUID noteId) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        Note note = Note.findById(noteId);
        if (note == null || !canAccessNote(note, user)) {
            return notFound();
        }
        List<TodoChecklistParser.ParsedItem> parsed = TodoChecklistParser.parse(note.content);
        if (parsed.isEmpty()) {
            return badRequest("This note has no checklist items (- [ ] or - [x]).");
        }

        TodoList list = new TodoList();
        list.ownerId = user.id;
        list.title = normalizeTitle(note.title) == null ? "TODO list" : note.title.trim();
        list.noteId = note.id;
        list.persist();
        list.flush();

        Deque<ParentRef> stack = new ArrayDeque<>();
        Map<UUID, Integer> counters = new HashMap<>();
        for (TodoChecklistParser.ParsedItem parsedItem : parsed) {
            while (!stack.isEmpty() && stack.peek().depth() >= parsedItem.depth()) {
                stack.pop();
            }
            UUID parentId = stack.isEmpty() ? null : stack.peek().id();
            int position = counters.merge(parentId, 1, Integer::sum) - 1;
            TodoItem item = new TodoItem();
            item.listId = list.id;
            item.parentId = parentId;
            item.text = parsedItem.text();
            item.checked = parsedItem.checked();
            item.position = position;
            item.priority = "NONE";
            item.persist();
            // Flush parent-before-child: the raw UUID self-FK is not mapped as
            // a JPA association, so Hibernate cannot order these inserts itself.
            item.flush();
            stack.push(new ParentRef(parsedItem.depth(), item.id));
        }

        return Response.status(Response.Status.CREATED)
                .entity(toDetail(list, TodoItem.list("listId", list.id)))
                .build();
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    @POST
    @Path("/{id}/items")
    @Transactional
    public Response addItem(@PathParam("id") UUID id, CreateItemRequest request) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        TodoList list = findAccessibleList(id, user);
        if (list == null) {
            return notFound();
        }
        if (request == null) {
            return badRequest("text is required");
        }
        String text = normalizeText(request.text());
        if (text == null) {
            return badRequest("text is required");
        }
        String priority = normalizePriority(request.priority());
        if (priority == null) {
            return badRequest("priority must be NONE, LOW, MEDIUM or HIGH");
        }
        String dueDateValue = request.dueDate();
        if (dueDateValue != null && !dueDateValue.isBlank() && parseDueDate(dueDateValue) == null) {
            return badRequest("dueDate must be an ISO date (YYYY-MM-DD)");
        }
        if (request.parentId() != null) {
            TodoItem parent = TodoItem.findById(request.parentId());
            if (parent == null || !parent.listId.equals(list.id)) {
                return badRequest("parent item not found in this list");
            }
        }

        TodoItem item = new TodoItem();
        item.listId = list.id;
        item.parentId = request.parentId();
        item.text = text;
        item.priority = priority;
        item.dueDate = parseDueDate(dueDateValue);
        item.position = nextPosition(list.id, item.parentId);
        item.persist();
        item.flush();
        touchList(list);
        return Response.status(Response.Status.CREATED).entity(toView(item)).build();
    }

    @PUT
    @Path("/{id}/items/{itemId}")
    @Transactional
    public Response updateItem(@PathParam("id") UUID id, @PathParam("itemId") UUID itemId,
                               UpdateItemRequest request) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        TodoList list = findAccessibleList(id, user);
        if (list == null) {
            return notFound();
        }
        TodoItem item = TodoItem.findById(itemId);
        if (item == null || !item.listId.equals(list.id)) {
            return notFound();
        }
        if (request == null || request.version() == null) {
            return badRequest("version is required");
        }
        if (request.version() != item.version) {
            return conflict("TODO item was modified; reload and try again");
        }
        if (request.text() != null) {
            String text = normalizeText(request.text());
            if (text == null) {
                return badRequest("text is required");
            }
            item.text = text;
        }
        if (request.checked() != null) {
            item.checked = request.checked();
        }
        if (request.priority() != null) {
            String priority = normalizePriority(request.priority());
            if (priority == null) {
                return badRequest("priority must be NONE, LOW, MEDIUM or HIGH");
            }
            item.priority = priority;
        }
        if (request.dueDate() != null) {
            if (request.dueDate().isBlank()) {
                item.dueDate = null;
            } else {
                LocalDate dueDate = parseDueDate(request.dueDate());
                if (dueDate == null) {
                    return badRequest("dueDate must be an ISO date (YYYY-MM-DD)");
                }
                item.dueDate = dueDate;
            }
        }
        item.flush();
        touchList(list);
        return Response.ok(toView(item)).build();
    }

    @DELETE
    @Path("/{id}/items/{itemId}")
    @Transactional
    public Response deleteItem(@PathParam("id") UUID id, @PathParam("itemId") UUID itemId) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        TodoList list = findAccessibleList(id, user);
        if (list == null) {
            return notFound();
        }
        TodoItem item = TodoItem.findById(itemId);
        if (item == null || !item.listId.equals(list.id)) {
            return notFound();
        }
        // The database cascades to child items through parent_id.
        item.delete();
        touchList(list);
        return Response.noContent().build();
    }

    /**
     * Persist a new tree order. The request must contain every item in the list
     * exactly once, in display order; positions are assigned per parent in that
     * order. Parent cycles are rejected.
     */
    @PUT
    @Path("/{id}/items/reorder")
    @Transactional
    public Response reorder(@PathParam("id") UUID id, ReorderRequest request) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return unauthorized();
        }
        TodoList list = findAccessibleList(id, user);
        if (list == null) {
            return notFound();
        }
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return badRequest("items are required");
        }

        List<TodoItem> existing = TodoItem.list("listId", list.id);
        Map<UUID, TodoItem> byId = existing.stream()
                .collect(Collectors.toMap(item -> item.id, item -> item));
        Map<UUID, UUID> parentById = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (ReorderItem entry : request.items()) {
            if (entry == null || entry.id() == null || !byId.containsKey(entry.id())) {
                return badRequest("item not found in this list");
            }
            if (!seen.add(entry.id())) {
                return badRequest("duplicate item id");
            }
            if (entry.parentId() != null && !byId.containsKey(entry.parentId())) {
                return badRequest("parent item not found in this list");
            }
            parentById.put(entry.id(), entry.parentId());
        }
        if (seen.size() != existing.size()) {
            return badRequest("reorder must include every item");
        }
        for (UUID itemId : parentById.keySet()) {
            Set<UUID> path = new HashSet<>();
            UUID cursor = itemId;
            while (cursor != null) {
                if (!path.add(cursor)) {
                    return badRequest("parent cycle detected");
                }
                cursor = parentById.get(cursor);
            }
        }

        Map<UUID, Integer> counters = new HashMap<>();
        for (ReorderItem entry : request.items()) {
            int position = counters.merge(entry.parentId(), 1, Integer::sum) - 1;
            TodoItem item = byId.get(entry.id());
            item.parentId = entry.parentId();
            item.position = position;
        }
        list.flush();
        touchList(list);
        return Response.ok(toDetail(list, TodoItem.list("listId", list.id))).build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record ParentRef(int depth, UUID id) {}

    private User getAuthenticatedUser() {
        return sessions.resolve(headers.getCookies().get(SessionService.COOKIE_NAME));
    }

    private boolean canAccessNote(Note note, User user) {
        return user.superuser || note.ownerId.equals(user.id);
    }

    private TodoList findAccessibleList(UUID id, User user) {
        if (id == null) {
            return null;
        }
        TodoList list = TodoList.findById(id);
        if (list == null || (!user.superuser && !list.ownerId.equals(user.id))) {
            return null;
        }
        return list;
    }

    private Map<UUID, List<TodoItem>> itemsByList(List<TodoList> lists) {
        List<UUID> listIds = lists.stream().map(list -> list.id).toList();
        if (listIds.isEmpty()) {
            return Map.of();
        }
        return TodoItem.<TodoItem>list("listId in ?1", listIds).stream()
                .collect(Collectors.groupingBy(item -> item.listId));
    }

    private Map<UUID, String> noteTitles(List<TodoList> lists) {
        Set<UUID> noteIds = lists.stream()
                .map(list -> list.noteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (noteIds.isEmpty()) {
            return Map.of();
        }
        return Note.<Note>list("id in ?1", noteIds).stream()
                .collect(Collectors.toMap(note -> note.id, note -> note.title, (a, b) -> a));
    }

    /**
     * Null-safe title lookup: lists without a linked note have a null id, and
     * {@code Map.of().get(null)} throws (the bug behind the empty-list NPE).
     */
    static String noteTitleFor(UUID noteId, Map<UUID, String> noteTitles) {
        if (noteId == null || noteTitles == null) {
            return null;
        }
        return noteTitles.get(noteId);
    }

    private TodoListSummary toSummary(TodoList list, List<TodoItem> items, String noteTitle) {
        long open = items.stream().filter(item -> !item.checked).count();
        LocalDate today = LocalDate.now();
        long overdue = items.stream()
                .filter(item -> !item.checked && item.dueDate != null && item.dueDate.isBefore(today))
                .count();
        return new TodoListSummary(list.id, list.title, list.noteId, noteTitle, list.pinned,
                items.size(), open, overdue, list.version, list.createdAt, list.updatedAt);
    }

    private TodoListDetail toDetail(TodoList list, List<TodoItem> items) {
        String noteTitle = null;
        if (list.noteId != null) {
            Note note = Note.findById(list.noteId);
            noteTitle = note == null ? null : note.title;
        }
        return new TodoListDetail(list.id, list.title, list.noteId, noteTitle, list.pinned,
                list.version, list.createdAt, list.updatedAt, treeOrder(items));
    }

    /** Flatten the item tree in display order (roots first, then children). */
    static List<TodoItemView> treeOrder(List<TodoItem> items) {
        Map<UUID, List<TodoItem>> children = new HashMap<>();
        List<TodoItem> roots = new ArrayList<>();
        Set<UUID> ids = items.stream().map(item -> item.id).collect(Collectors.toSet());
        for (TodoItem item : items) {
            if (item.parentId == null || !ids.contains(item.parentId)) {
                roots.add(item);
            } else {
                children.computeIfAbsent(item.parentId, key -> new ArrayList<>()).add(item);
            }
        }
        Comparator<TodoItem> byPosition = Comparator
                .comparingInt((TodoItem item) -> item.position)
                .thenComparing(item -> item.createdAt);
        roots.sort(byPosition);

        List<TodoItemView> ordered = new ArrayList<>();
        Set<UUID> emitted = new HashSet<>();
        for (TodoItem root : roots) {
            append(root, children, byPosition, ordered, emitted);
        }
        // Defensive: a malformed/cyclic tree must not hide items.
        for (TodoItem item : items) {
            if (emitted.add(item.id)) {
                ordered.add(toView(item));
            }
        }
        return ordered;
    }

    private static void append(TodoItem item, Map<UUID, List<TodoItem>> children,
                               Comparator<TodoItem> byPosition, List<TodoItemView> ordered,
                               Set<UUID> emitted) {
        if (!emitted.add(item.id)) {
            return;
        }
        ordered.add(toView(item));
        List<TodoItem> kids = children.get(item.id);
        if (kids == null) {
            return;
        }
        kids.sort(byPosition);
        for (TodoItem child : kids) {
            append(child, children, byPosition, ordered, emitted);
        }
    }

    private static TodoItemView toView(TodoItem item) {
        return new TodoItemView(item.id, item.parentId, item.text, item.checked, item.position,
                item.priority, item.dueDate, item.version, item.createdAt, item.updatedAt);
    }

    private static int nextPosition(UUID listId, UUID parentId) {
        List<TodoItem> siblings = parentId == null
                ? TodoItem.list("listId = ?1 and parentId is null", listId)
                : TodoItem.list("listId = ?1 and parentId = ?2", listId, parentId);
        return siblings.stream().mapToInt(item -> item.position).max().orElse(-1) + 1;
    }

    /** Bump the list's activity timestamp without changing its optimistic version. */
    private void touchList(TodoList list) {
        TodoList.update("updatedAt = ?1 where id = ?2", Instant.now(), list.id);
    }

    static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String value = title.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > MAX_TITLE_LENGTH ? value.substring(0, MAX_TITLE_LENGTH) : value;
    }

    static String normalizeText(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH) : value;
    }

    static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return "NONE";
        }
        String value = priority.trim().toUpperCase();
        return PRIORITIES.contains(value) ? value : null;
    }

    static LocalDate parseDueDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED).build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(message).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT).entity(message).build();
    }
}
