package li.ktt;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.database.datagrid.DataConsumer.Column;
import com.intellij.database.datagrid.DataConsumer.Row;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.database.util.DbImplUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.HashSet;
import li.ktt.datagrid.ResultSetHelper;
import li.ktt.settings.ExtractorProperties;
import li.ktt.settings.ProjectSettings;
import li.ktt.xml.XmlGenerator;
import li.ktt.xml.XmlOutput;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class QueryToXMLConverter extends PsiElementBaseIntentionAction implements IntentionAction {

    private int TABLE_SCHEME_INDEX = 2;

    @NotNull
    public String getText() {
        return "Convert query to XML";
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
        return getText();
    }

    @Override
    public void invoke(@NotNull final Project project,
                       final Editor editor,
                       @NotNull final PsiElement psiElement) throws IncorrectOperationException {

        ExtractorProperties extractorProperties =
                ProjectSettings.getExtractorProperties(SimpleDataContext.getProjectContext(project));

        final List<DbDataSource> dataSources = DbPsiFacade.getInstance(project).getDataSources();

        if (dataSources.isEmpty()) {
            showPopup(editor, MessageType.ERROR, "Could not find datasource.");
            return;
        }

        final String selectedDataSourceName = extractorProperties.getSelectedDataSourceName();

        DbDataSource dataSource = null;

        if (StringUtil.isNotEmpty(selectedDataSourceName)) {
            for (final DbDataSource source : dataSources) {
                if (source.getName().equals(selectedDataSourceName)) {
                    dataSource = source;
                    break;
                }
            }
        }

        if (dataSource == null || StringUtil.isEmpty(selectedDataSourceName)) {
            dataSource = dataSources.get(0);
            showPopup(editor, MessageType.INFO, "Using first found datasource: " + dataSource.getName() + ". Please change default one in options.");
        }

        final String query = StringUtil.trim(editor.getSelectionModel().getSelectedText());

        try (final Connection connection = DbImplUtil.getConnection(dataSource)) {
            try (final Statement statement = connection == null ? null : connection.createStatement()) {
                try (final ResultSet resultSet = statement == null ? null : statement.executeQuery(query)) {

                    if (resultSet == null) {
                        showPopup(editor, MessageType.ERROR, "Connection error");
                        return;
                    }

                    final ResultSetMetaData metaData = resultSet.getMetaData();

                    Set<String> tableNames = getTablesNamesFromQuery(metaData);
                    if (tableNames.size() != 1) {
                        showPopup(editor, MessageType.ERROR, "Only one table queries are supported.");
                        return;
                    }

                    final List<Column> columns = constructColumns(metaData);
                    final List<Row> rows = constructRows(metaData, resultSet);
                    final String tableName = metaData.getTableName(1);
                    final String schema = StringUtil.isNotEmpty(metaData.getSchemaName(1))
                            ? metaData.getSchemaName(1)
                            : getSchemaName(connection, tableName);

                    final ResultSetHelper resultSetHelper =
                            new ResultSetHelper(extractorProperties,
                                                schema,
                                                tableName,
                                                columns,
                                                rows);
                    XmlGenerator xmlGenerator =
                            new XmlGenerator(extractorProperties, resultSetHelper);
                    xmlGenerator.appendRows();

                    replaceSelection(editor, xmlGenerator.getOutput());
                } catch (SQLException e) {
                    showPopup(editor, MessageType.ERROR, e.getLocalizedMessage());
                }
            }
        } catch (Exception e) {
            showPopup(editor, MessageType.ERROR, e.getLocalizedMessage());
        }
    }

    @NotNull
    private Set<String> getTablesNamesFromQuery(final ResultSetMetaData metaData)
            throws SQLException {
        Set<String> tableNames = new HashSet<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            if (StringUtil.isNotEmpty(metaData.getTableName(i))) {
                tableNames.add(metaData.getTableName(i));
            }
        }
        return tableNames;
    }

    private String getSchemaName(final Connection connection, final String tableName)
            throws SQLException {
        String tableType[] = {"TABLE"};
        final DatabaseMetaData connectionMetaData = connection.getMetaData();
        try (ResultSet result = connectionMetaData.getTables(null, null, tableName, tableType)) {
            while (result.next()) {
                return result.getString(TABLE_SCHEME_INDEX);
            }
        }
        return null;
    }

    @NotNull
    private List<Row> constructRows(final ResultSetMetaData metaData,
                                    final ResultSet resultSet) throws SQLException {
        final List<Row> rows = new LinkedList<>();
        int rowNum = 0;
        while (resultSet.next()) {
            List<Object> values = new LinkedList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                values.add(resultSet.getString(i));
            }
            rows.add(new Row(rowNum - 1, values.toArray()));
            ++rowNum;
        }
        return rows;
    }

    @NotNull
    private List<Column> constructColumns(final ResultSetMetaData metaData)
            throws SQLException {
        final List<Column> columns = new LinkedList<>();

        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            columns.add(new Column(i - 1,
                                   metaData.getColumnName(i),
                                   metaData.getColumnType(i),
                                   metaData.getColumnTypeName(i),
                                   metaData.getColumnClassName(i)));
        }
        return columns;
    }

    private void replaceSelection(final Editor editor, final XmlOutput xmlOutput) {
        final SelectionModel selectionModel = editor.getSelectionModel();
        editor.getDocument()
              .replaceString(selectionModel.getSelectionStart(),
                             selectionModel.getSelectionEnd(),
                             xmlOutput.getText());
        editor.getSelectionModel().removeSelection();
    }

    @Override
    public boolean isAvailable(@NotNull final Project project,
                               final Editor editor,
                               @NotNull final PsiElement psiElement) {
        return StringUtil.isNotEmpty(editor.getSelectionModel().getSelectedText())
                && editor.getDocument().getText().startsWith("<dataset>");
    }

    private void showPopup(final Editor editor,
                           final MessageType messageType,
                           final String message) {
        JBPopupFactory.getInstance()
                      .createHtmlTextBalloonBuilder(message, messageType, null)
                      .setFadeoutTime(7500)
                      .createBalloon()
                      .show(JBPopupFactory.getInstance().guessBestPopupLocation(editor),
                            Balloon.Position.atRight);
    }
}
