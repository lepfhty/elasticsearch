/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.facet.histogram;

import com.carrotsearch.hppc.DoubleObjectOpenHashMap;
import org.apache.lucene.index.AtomicReaderContext;
import org.elasticsearch.common.recycler.Recycler;
import org.elasticsearch.index.fielddata.DoubleValues;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.search.facet.DoubleFacetAggregatorBase;
import org.elasticsearch.search.facet.FacetExecutor;
import org.elasticsearch.search.facet.InternalFacet;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A histogram facet collector that uses different fields for the key and the value.
 */
public class ValueHistogramFacetExecutor extends FacetExecutor {

    private final IndexNumericFieldData keyIndexFieldData;
    private final IndexNumericFieldData valueIndexFieldData;
    private final HistogramFacet.ComparatorType comparatorType;
    private final double interval;

    final Recycler.V<DoubleObjectOpenHashMap<InternalFullHistogramFacet.FullEntry>> entries;

    public ValueHistogramFacetExecutor(IndexNumericFieldData keyIndexFieldData, IndexNumericFieldData valueIndexFieldData, double interval, HistogramFacet.ComparatorType comparatorType, SearchContext context) {
        this.comparatorType = comparatorType;
        this.keyIndexFieldData = keyIndexFieldData;
        this.valueIndexFieldData = valueIndexFieldData;
        this.interval = interval;
        this.entries = context.cacheRecycler().doubleObjectMap(-1);
    }

    @Override
    public Collector collector() {
        return new Collector();
    }

    @Override
    public InternalFacet buildFacet(String facetName) {
        List<InternalFullHistogramFacet.FullEntry> entries1 = new ArrayList<InternalFullHistogramFacet.FullEntry>(entries.v().size());
        final boolean [] states = entries.v().allocated;
        final Object[] values = entries.v().values;

        for (int i = 0; i < states.length; i++) {
            if (states[i]) {
                InternalFullHistogramFacet.FullEntry value = (InternalFullHistogramFacet.FullEntry) values[i];
                entries1.add(value);
            }
        }
        entries.release();
        return new InternalFullHistogramFacet(facetName, comparatorType, entries1);
    }

    class Collector extends FacetExecutor.Collector {

        private final HistogramProc histoProc;
        private DoubleValues keyValues;

        public Collector() {
            this.histoProc = new HistogramProc(interval, entries.v());
        }

        @Override
        public void setNextReader(AtomicReaderContext context) throws IOException {
            keyValues = keyIndexFieldData.load(context).getDoubleValues();
            histoProc.valueValues = valueIndexFieldData.load(context).getDoubleValues();
        }

        @Override
        public void collect(int doc) throws IOException {
            histoProc.onDoc(doc, keyValues);
        }

        @Override
        public void postCollection() {
        }
    }

    public final static class HistogramProc extends DoubleFacetAggregatorBase {

        final double interval;
        final DoubleObjectOpenHashMap<InternalFullHistogramFacet.FullEntry> entries;

        DoubleValues valueValues;

        final ValueAggregator valueAggregator = new ValueAggregator();

        public HistogramProc(double interval, DoubleObjectOpenHashMap<InternalFullHistogramFacet.FullEntry> entries) {
            this.interval = interval;
            this.entries = entries;
        }

        @Override
        public void onValue(int docId, double value) {
            double bucket = FullHistogramFacetExecutor.bucket(value, interval);
            InternalFullHistogramFacet.FullEntry entry = entries.get(bucket);
            if (entry == null) {
                entry = new InternalFullHistogramFacet.FullEntry(bucket, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 0);
                entries.put(bucket, entry);
            }
            entry.count++;
            valueAggregator.entry = entry;
            valueAggregator.onDoc(docId, valueValues);
        }

        public final static class ValueAggregator extends DoubleFacetAggregatorBase {

            InternalFullHistogramFacet.FullEntry entry;

            @Override
            public void onValue(int docId, double value) {
                entry.totalCount++;
                entry.total += value;
                if (value < entry.min) {
                    entry.min = value;
                }
                if (value > entry.max) {
                    entry.max = value;
                }
            }
        }
    }
}
