import { Alert, Button, Divider, FileInput, Group, List, Loader, Stack, Text, Title } from '@mantine/core'
import { IconArrowLeft, IconDownload } from '@tabler/icons-react'

/**
 * Import / Export view shown in the main content pane (not a modal). The top
 * half imports notes; the bottom half downloads the full ZIP export.
 */
export default function ImportExportPanel({
  importResult,
  importLoading,
  exporting,
  overQuota,
  onImportJoplin,
  onImportMarkdown,
  onExport,
  onClose,
  onDismissResult,
}) {
  return (
    <Stack gap="md" style={{ width: '100%', flex: 1, minWidth: 0 }}>
      <Group justify="space-between" align="center" wrap="wrap" gap="xs">
        <Title order={3} size="h4">Import / Export notes</Title>
        <Button
          variant="default"
          size="sm"
          leftSection={<IconArrowLeft size={16} />}
          onClick={onClose}
          style={{ flex: '0 0 auto' }}
        >
          Back to notes
        </Button>
      </Group>

      <Divider />

      {importResult && (
        <Alert
          color={importResult.errors === 0 ? 'primary' : 'yellow'}
          withCloseButton
          onClose={onDismissResult}
        >
          <Stack gap="xs">
            <Text size="sm">
              Imported {importResult.imported} note{importResult.imported === 1 ? '' : 's'}
              {importResult.attachments > 0 && (
                ` and ${importResult.attachments} attachment${importResult.attachments === 1 ? '' : 's'}`
              )}
              {importResult.errors > 0 && ` with ${importResult.errors} error${importResult.errors === 1 ? '' : 's'}`}
            </Text>
            {importResult.messages.length > 0 && (
              <List size="sm">
                {importResult.messages.map((msg, idx) => (
                  <List.Item key={idx}>{msg}</List.Item>
                ))}
              </List>
            )}
          </Stack>
        </Alert>
      )}

      {importLoading && (
        <Stack align="center" gap="md" py="xl">
          <Loader />
          <Text c="dimmed" size="sm">Importing...</Text>
        </Stack>
      )}

      {!importLoading && !importResult && (
        <Stack gap="md">
          {overQuota && (
            <Alert color="yellow" title="Storage limit reached">
              <Text size="sm">
                Importing is unavailable until you free up space or upgrade your plan.
                Exporting still works.
              </Text>
            </Alert>
          )}

          <Stack gap="sm">
            <Text fw={500} size="sm">Import from Joplin (.jex)</Text>
            <Text size="xs" c="dimmed">
              Export from Joplin, then upload the .jex file. Folder hierarchy will be converted to tags, and
              attachments referenced in your notes are imported and linked automatically.
            </Text>
            <FileInput
              placeholder="Choose .jex file"
              accept=".jex"
              onChange={onImportJoplin}
              disabled={importLoading || overQuota}
              size="sm"
            />
          </Stack>

          <Stack gap="sm">
            <Text fw={500} size="sm">Import Markdown file (.md)</Text>
            <Text size="xs" c="dimmed">
              Upload a single markdown file. The first line will be used as the title.
            </Text>
            <FileInput
              placeholder="Choose .md file"
              accept=".md"
              onChange={onImportMarkdown}
              disabled={importLoading || overQuota}
              size="sm"
            />
          </Stack>
        </Stack>
      )}

      <Divider />

      <Stack gap="sm">
        <Text fw={500} size="sm">Export all notes</Text>
        <Text size="xs" c="dimmed">
          Download a ZIP archive with one JSON file per note and every attachment.
          Each note file contains its title, tags, pinned state, linked attachments and content.
        </Text>
        <Group mt="xs" gap="xs">
          <Button
            leftSection={<IconDownload size={14} />}
            onClick={onExport}
            loading={exporting}
            disabled={importLoading}
            size="xs"
            w="fit-content"
          >
            Export .zip
          </Button>
        </Group>
      </Stack>
    </Stack>
  )
}
