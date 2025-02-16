/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.aggregations.bucket.filter;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.sandbox.search.IndexSortSortedNumericDocValuesRangeQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PointRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

/**
 * Adapts a Lucene {@link Query} to the behaviors used be the
 * {@link FiltersAggregator}. In general we try to delegate to {@linkplain Query}
 * when we don't have a special optimization.
 */
public class QueryToFilterAdapter<Q extends Query> {
    /**
     * Build a filter for the query against the provided searcher.
     * <p>
     * Note: This method rewrites the query against the {@link IndexSearcher}
     */
    public static QueryToFilterAdapter<?> build(IndexSearcher searcher, String key, Query query) throws IOException {
        // Wrapping with a ConstantScoreQuery enables a few more rewrite
        // rules as of Lucene 9.2
        query = searcher.rewrite(new ConstantScoreQuery(query));
        if (query instanceof ConstantScoreQuery) {
            /*
             * Unwrap constant score because it gets in the way of us
             * understanding what the queries are trying to do and we
             * don't use the score at all anyway. Effectively we always
             * run in constant score mode.
             */
            query = ((ConstantScoreQuery) query).getQuery();
        }
        if (query instanceof MatchAllDocsQuery) {
            return new MatchAllQueryToFilterAdapter(searcher, key, (MatchAllDocsQuery) query);
        }
        if (query instanceof MatchNoDocsQuery) {
            return new MatchNoneQueryToFilterAdapter(searcher, key, (MatchNoDocsQuery) query);
        }
        return new QueryToFilterAdapter<>(searcher, key, query);
    }

    private final IndexSearcher searcher;
    private final String key;
    private final Q query;
    /**
     * The weight for the query or {@code null} if we haven't built it. Use
     * {@link #weight()} to build it when needed.
     */
    private Weight weight;
    protected int segmentsCountedInConstantTime;

    QueryToFilterAdapter(IndexSearcher searcher, String key, Q query) {
        this.searcher = searcher;
        this.key = key;
        this.query = query;
    }

    /**
     * The query we're adapting.
     * <p>
     * Subclasses should use this to fetch the query when making query
     * specific optimizations.
     */
    Q query() {
        return query;
    }

    /**
     * Is this an inefficient union of the top level query with the filter?
     * If the top level query if complex we can't efficiently merge it with
     * the filter. If we can't do that it is likely faster to just run the
     * "native" aggregation implementation rather than go filter by filter.
     */
    public boolean isInefficientUnion() {
        return false;
    }

    /**
     * Key for this filter.
     */
    public final String key() {
        return key;
    }

    /**
     * Searcher that this filter is targeting.
     */
    protected final IndexSearcher searcher() {
        return searcher;
    }

    /**
     * Make a filter that matches this filter and the provided query.
     * <p>
     * Note: This method rewrites the query against the {@link IndexSearcher}.
     */
    QueryToFilterAdapter<?> union(Query extraQuery) throws IOException {
        /*
         * Wrapping with a ConstantScoreQuery enables a few more rewrite
         * rules as of Lucene 9.2.
         * It'd be *wonderful* if Lucene could do fancy optimizations
         * when merging queries like combining ranges but it doesn't at
         * the moment. Admittedly, we have a much more limited problem.
         * We don't care about score here at all. We know which queries
         * it's worth spending time to optimize because we know which aggs
         * rewrite into this one.
         */
        extraQuery = searcher().rewrite(new ConstantScoreQuery(extraQuery));
        Query unwrappedExtraQuery = unwrap(extraQuery);
        if (unwrappedExtraQuery instanceof MatchAllDocsQuery) {
            return this;
        }
        Query unwrappedQuery = unwrap(query);
        if (unwrappedQuery instanceof PointRangeQuery && unwrappedExtraQuery instanceof PointRangeQuery) {
            Query merged = MergedPointRangeQuery.merge((PointRangeQuery) unwrappedQuery, (PointRangeQuery) unwrappedExtraQuery);
            if (merged != null) {
                // Should we rewrap here?
                return new QueryToFilterAdapter<>(searcher(), key(), merged);
            }
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(query, BooleanClause.Occur.FILTER);
        builder.add(extraQuery, BooleanClause.Occur.FILTER);
        return new QueryToFilterAdapter<>(searcher(), key(), builder.build()) {
            public boolean isInefficientUnion() {
                return true;
            }
        };
    }

    private static Query unwrap(Query query) {
        while (true) {
            if (query instanceof ConstantScoreQuery) {
                query = ((ConstantScoreQuery) query).getQuery();
                continue;
            }
            if (query instanceof IndexSortSortedNumericDocValuesRangeQuery) {
                query = ((IndexSortSortedNumericDocValuesRangeQuery) query).getFallbackQuery();
                continue;
            }
            if (query instanceof IndexOrDocValuesQuery) {
                query = ((IndexOrDocValuesQuery) query).getIndexQuery();
                continue;
            }
            return query;
        }
    }

    /**
     * Build a predicate that the "compatible" implementation of the
     * {@link FiltersAggregator} will use to figure out if the filter matches.
     * <p>
     * Consumers of this method will always call it with non-negative,
     * increasing {@code int}s. A sequence like {@code 0, 1, 7, 8, 10} is fine.
     * It won't call with {@code 0, 1, 0} or {@code -1, 0, 1}.
     */
    @SuppressWarnings("resource")  // Closing the reader is someone else's problem
    IntPredicate matchingDocIds(LeafReaderContext ctx) throws IOException {
        return Lucene.asSequentialAccessBits(ctx.reader().maxDoc(), weight().scorerSupplier(ctx))::get;
    }

    /**
     * Count the number of documents that match this filter in a leaf.
     */
    long count(LeafReaderContext ctx, FiltersAggregator.Counter counter, Bits live) throws IOException {
        /*
         * weight().count will return the count of matches for ctx if it can do
         * so in constant time, otherwise -1. The Weight is responsible for
         * all of the cases where it can't return an accurate count *except*
         * the doc_count field. That thing is ours, not Lucene's.
         *
         * For example, TermQuery will return -1 if there are deleted docs,
         * otherwise it'll return number of documents with the term from the
         * term statistics. MatchAllDocs will call `ctx.reader().numdocs()`
         * to get the number of live docs.
         */
        if (counter.docCount.alwaysOne()) {
            int count = weight().count(ctx);
            if (count != -1) {
                segmentsCountedInConstantTime++;
                return count;
            }
        }
        BulkScorer scorer = weight().bulkScorer(ctx);
        if (scorer == null) {
            // No hits in this segment.
            return 0;
        }
        scorer.score(counter, live);
        return counter.readAndReset(ctx);
    }

    /**
     * Collect all documents that match this filter in this leaf.
     */
    void collect(LeafReaderContext ctx, LeafCollector collector, Bits live) throws IOException {
        BulkScorer scorer = weight().bulkScorer(ctx);
        if (scorer == null) {
            // No hits in this segment.
            return;
        }
        scorer.score(collector, live);
    }

    /**
     * Collect profiling information for this filter. Rhymes with
     * {@link Aggregator#collectDebugInfo(BiConsumer)}.
     * <p>
     * Well behaved implementations will always call the superclass
     * implementation just in case it has something interesting. They will
     * also only add objects which can be serialized with
     * {@link StreamOutput#writeGenericValue(Object)} and
     * {@link XContentBuilder#value(Object)}. And they'll have an integration
     * test.
     */
    void collectDebugInfo(BiConsumer<String, Object> add) {
        add.accept("query", query.toString());
        add.accept("segments_counted_in_constant_time", segmentsCountedInConstantTime);
    }

    private Weight weight() throws IOException {
        if (weight == null) {
            weight = searcher().createWeight(query, ScoreMode.COMPLETE_NO_SCORES, 1.0f);
        }
        return weight;
    }
}
