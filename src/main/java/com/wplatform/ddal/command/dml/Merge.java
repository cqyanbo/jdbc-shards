/*
 * Copyright 2014-2015 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wplatform.ddal.command.dml;

import java.util.ArrayList;

import com.wplatform.ddal.command.Command;
import com.wplatform.ddal.command.CommandInterface;
import com.wplatform.ddal.command.Prepared;
import com.wplatform.ddal.command.expression.Expression;
import com.wplatform.ddal.command.expression.Parameter;
import com.wplatform.ddal.dbobject.Right;
import com.wplatform.ddal.dbobject.index.Index;
import com.wplatform.ddal.dbobject.table.Column;
import com.wplatform.ddal.dbobject.table.Table;
import com.wplatform.ddal.engine.Session;
import com.wplatform.ddal.message.DbException;
import com.wplatform.ddal.message.ErrorCode;
import com.wplatform.ddal.result.ResultInterface;
import com.wplatform.ddal.result.Row;
import com.wplatform.ddal.util.New;
import com.wplatform.ddal.util.StatementBuilder;
import com.wplatform.ddal.value.Value;

/**
 * This class represents the statement
 * MERGE
 */
public class Merge extends Prepared {

    private final ArrayList<Expression[]> list = New.arrayList();
    private Table table;
    private Column[] columns;
    private Column[] keys;
    private Query query;
    private Prepared update;

    public Merge(Session session) {
        super(session);
    }

    @Override
    public void setCommand(Command command) {
        super.setCommand(command);
        if (query != null) {
            query.setCommand(command);
        }
    }

    public void setTable(Table table) {
        this.table = table;
    }

    public void setColumns(Column[] columns) {
        this.columns = columns;
    }

    public void setKeys(Column[] keys) {
        this.keys = keys;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    /**
     * Add a row to this merge statement.
     *
     * @param expr the list of values
     */
    public void addRow(Expression[] expr) {
        list.add(expr);
    }

    @Override
    public int update() {
        int count;
        session.getUser().checkRight(table, Right.INSERT);
        session.getUser().checkRight(table, Right.UPDATE);
        setCurrentRowNumber(0);
        if (list.size() > 0) {
            count = 0;
            for (int x = 0, size = list.size(); x < size; x++) {
                setCurrentRowNumber(x + 1);
                Expression[] expr = list.get(x);
                Row newRow = table.getTemplateRow();
                for (int i = 0, len = columns.length; i < len; i++) {
                    Column c = columns[i];
                    int index = c.getColumnId();
                    Expression e = expr[i];
                    if (e != null) {
                        // e can be null (DEFAULT)
                        try {
                            Value v = c.convert(e.getValue(session));
                            newRow.setValue(index, v);
                        } catch (DbException ex) {
                            throw setRow(ex, count, getSQL(expr));
                        }
                    }
                }
                merge(newRow);
                count++;
            }
        } else {
            ResultInterface rows = query.query(0);
            count = 0;
            //table.lock(session, true, false);
            while (rows.next()) {
                count++;
                Value[] r = rows.currentRow();
                Row newRow = table.getTemplateRow();
                setCurrentRowNumber(count);
                for (int j = 0; j < columns.length; j++) {
                    Column c = columns[j];
                    int index = c.getColumnId();
                    try {
                        Value v = c.convert(r[j]);
                        newRow.setValue(index, v);
                    } catch (DbException ex) {
                        throw setRow(ex, count, getSQL(r));
                    }
                }
                merge(newRow);
            }
            rows.close();
        }
        return count;
    }

    private void merge(Row row) {
        ArrayList<Parameter> k = update.getParameters();
        for (int i = 0; i < columns.length; i++) {
            Column col = columns[i];
            Value v = row.getValue(col.getColumnId());
            Parameter p = k.get(i);
            p.setValue(v);
        }
        for (int i = 0; i < keys.length; i++) {
            Column col = keys[i];
            Value v = row.getValue(col.getColumnId());
            if (v == null) {
                throw DbException.get(ErrorCode.COLUMN_CONTAINS_NULL_VALUES_1, col.getSQL());
            }
            Parameter p = k.get(columns.length + i);
            p.setValue(v);
        }
        int count = update.update();
        if (count == 0) {
            try {
                table.validateConvertUpdateSequence(session, row);
                //boolean done = table.fireBeforeRow(session, null, row);
                //if (!done) {
                //    table.lock(session, true, false);
                //    table.addRow(session, row);
                //    table.fireAfterRow(session, null, row, false);
                //}
            } catch (DbException e) {
                if (e.getErrorCode() == ErrorCode.DUPLICATE_KEY_1) {
                    // possibly a concurrent merge or insert
                    Index index = (Index) e.getSource();
                    if (index != null) {
                        // verify the index columns match the key
                        Column[] indexColumns = index.getColumns();
                        boolean indexMatchesKeys = false;
                        if (indexColumns.length <= keys.length) {
                            for (int i = 0; i < indexColumns.length; i++) {
                                if (indexColumns[i] != keys[i]) {
                                    indexMatchesKeys = false;
                                    break;
                                }
                            }
                        }
                        if (indexMatchesKeys) {
                            throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, table.getName());
                        }
                    }
                }
                throw e;
            }
        } else if (count != 1) {
            throw DbException.get(ErrorCode.DUPLICATE_KEY_1, table.getSQL());
        }
    }

    @Override
    public String getPlanSQL() {
        StatementBuilder buff = new StatementBuilder("MERGE INTO ");
        buff.append(table.getSQL()).append('(');
        for (Column c : columns) {
            buff.appendExceptFirst(", ");
            buff.append(c.getSQL());
        }
        buff.append(')');
        if (keys != null) {
            buff.append(" KEY(");
            buff.resetCount();
            for (Column c : keys) {
                buff.appendExceptFirst(", ");
                buff.append(c.getSQL());
            }
            buff.append(')');
        }
        buff.append('\n');
        if (list.size() > 0) {
            buff.append("VALUES ");
            int row = 0;
            for (Expression[] expr : list) {
                if (row++ > 0) {
                    buff.append(", ");
                }
                buff.append('(');
                buff.resetCount();
                for (Expression e : expr) {
                    buff.appendExceptFirst(", ");
                    if (e == null) {
                        buff.append("DEFAULT");
                    } else {
                        buff.append(e.getSQL());
                    }
                }
                buff.append(')');
            }
        } else {
            buff.append(query.getPlanSQL());
        }
        return buff.toString();
    }

    @Override
    public void prepare() {
        if (columns == null) {
            if (list.size() > 0 && list.get(0).length == 0) {
                // special case where table is used as a sequence
                columns = new Column[0];
            } else {
                columns = table.getColumns();
            }
        }
        if (list.size() > 0) {
            for (Expression[] expr : list) {
                if (expr.length != columns.length) {
                    throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
                }
                for (int i = 0; i < expr.length; i++) {
                    Expression e = expr[i];
                    if (e != null) {
                        expr[i] = e.optimize(session);
                    }
                }
            }
        } else {
            query.prepare();
            if (query.getColumnCount() != columns.length) {
                throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
            }
        }
        if (keys == null) {
            Index idx = table.getPrimaryKey();
            if (idx == null) {
                throw DbException.get(ErrorCode.CONSTRAINT_NOT_FOUND_1, "PRIMARY KEY");
            }
            keys = idx.getColumns();
        }
        StatementBuilder buff = new StatementBuilder("UPDATE ");
        buff.append(table.getSQL()).append(" SET ");
        for (Column c : columns) {
            buff.appendExceptFirst(", ");
            buff.append(c.getSQL()).append("=?");
        }
        buff.append(" WHERE ");
        buff.resetCount();
        for (Column c : keys) {
            buff.appendExceptFirst(" AND ");
            buff.append(c.getSQL()).append("=?");
        }
        String sql = buff.toString();
        update = session.prepare(sql);
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public ResultInterface queryMeta() {
        return null;
    }

    @Override
    public int getType() {
        return CommandInterface.MERGE;
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

}