/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"; you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.physical.pull.operators;

import com.google.common.annotations.VisibleForTesting;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.plan.SelectExpression;
import io.confluent.ksql.execution.streams.materialization.PullProcessingContext;
import io.confluent.ksql.execution.streams.materialization.TableRow;
import io.confluent.ksql.execution.transform.KsqlTransformer;
import io.confluent.ksql.execution.transform.select.SelectValueMapper;
import io.confluent.ksql.execution.transform.select.SelectValueMapperFactory;
import io.confluent.ksql.execution.transform.select.SelectValueMapperFactory.SelectValueMapperFactorySupplier;
import io.confluent.ksql.logging.processing.ProcessingLogger;
import io.confluent.ksql.planner.plan.PlanNode;
import io.confluent.ksql.planner.plan.PullProjectNode;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ProjectOperator extends AbstractPhysicalOperator implements UnaryPhysicalOperator {

  private final ProcessingLogger logger;
  private final SelectValueMapperFactorySupplier selectValueMapperFactorySupplier;
  private final PullProjectNode logicalNode;

  private AbstractPhysicalOperator child;
  private TableRow row;
  private KsqlTransformer<Object, GenericRow> transformer;

  public ProjectOperator(
      final ProcessingLogger logger,
      final PullProjectNode logicalNode
  ) {
    this(
        logger,
        logicalNode,
        SelectValueMapperFactory::create
    );
  }

  @VisibleForTesting
  ProjectOperator(
      final ProcessingLogger logger,
      final PullProjectNode logicalNode,
      final SelectValueMapperFactorySupplier selectValueMapperFactorySupplier
  ) {
    this.logger = Objects.requireNonNull(logger, "logger");
    this.logicalNode = Objects.requireNonNull(logicalNode, "logicalNode");
    this.selectValueMapperFactorySupplier = selectValueMapperFactorySupplier;
  }

  @Override
  public void open() {
    child.open();

    if (logicalNode.getIsSelectStar()) {
      return;
    }

    final SelectValueMapper<Object> select = selectValueMapperFactorySupplier.create(
        logicalNode.getSelectExpressions(),
        logicalNode.getCompiledSelectExpressions()
    );

    transformer = select.getTransformer(logger);
  }

  @Override
  public Object next() {
    row = (TableRow)child.next();
    if (row == null) {
      return null;
    }

    final GenericRow intermediate = PullPhysicalOperatorUtil.getIntermediateRow(
        row, logicalNode.getAddAdditionalColumnsToIntermediateSchema());

    if (logicalNode.getIsSelectStar()) {
      return createRowForSelectStar(intermediate);
    }

    final GenericRow mapped = transformer.transform(
        row.key(),
        intermediate,
        new PullProcessingContext(row.rowTime())
    );
    validateProjection(mapped, logicalNode.getSchema());

    return mapped.values();
  }

  @Override
  public void close() {
    child.close();
  }

  @Override
  public PlanNode getLogicalNode() {
    return logicalNode;
  }

  @Override
  public void addChild(final AbstractPhysicalOperator child) {
    if (this.child != null) {
      throw new UnsupportedOperationException("The project operator already has a child.");
    }
    Objects.requireNonNull(child, "child");
    this.child = child;
  }

  @Override
  public AbstractPhysicalOperator getChild() {
    return child;
  }

  @Override
  public AbstractPhysicalOperator getChild(final int index) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<AbstractPhysicalOperator> getChildren() {
    throw new UnsupportedOperationException();
  }

  private static void validateProjection(
      final GenericRow fullRow,
      final LogicalSchema schema
  ) {
    final int actual = fullRow.size();
    final int expected = schema.columns().size();
    if (actual != expected) {
      throw new IllegalStateException("Row column count mismatch."
                                          + " expected:" + expected
                                          + ", got:" + actual
      );
    }
  }

  // Optimization for select star, to avoid having to do code generation
  private List<?> createRowForSelectStar(final GenericRow intermediate) {
    final List<Object> rowList = new ArrayList<>();
    for (SelectExpression selectExpression : logicalNode.getSelectExpressions()) {
      final Optional<Column> column = logicalNode.getIntermediateSchema()
          .findValueColumn(selectExpression.getAlias());
      if (!column.isPresent()) {
        throw new IllegalStateException("Couldn't find alias in intermediate schema "
            + selectExpression.getAlias());
      }
      final int i = column.get().index();
      rowList.add(intermediate.get(i));
    }
    return rowList;
  }
}
