/* Copyright (c) 2011 Danish Maritime Authority
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this library.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dma.ais.store.materialize.views;

import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.RegularStatement;
import com.datastax.driver.core.querybuilder.Insert;
import com.datastax.driver.core.querybuilder.QueryBuilder;

import dk.dma.ais.binary.SixbitException;
import dk.dma.ais.message.AisMessageException;
import dk.dma.ais.packet.AisPacket;
import dk.dma.ais.store.materialize.AisMatSchema;
import dk.dma.ais.store.materialize.HashViewBuilder;
import dk.dma.ais.store.materialize.util.TypeSafeMapOfMaps;
import dk.dma.ais.store.materialize.util.TypeSafeMapOfMaps.Key3;
import dk.dma.enav.model.geometry.Position;
/**
 * 
 * @author Jens Tuxen
 *
 */
public class CellSourceTime implements HashViewBuilder {
    TypeSafeMapOfMaps<Key3<Integer, String, Integer>, Long> data = new TypeSafeMapOfMaps<>();

    TimeUnit unit;

    @Override
    public void accept(AisPacket aisPacket) {
        Objects.requireNonNull(aisPacket);
        Long timestamp = aisPacket.getBestTimestamp();
        String sourceid = Objects.requireNonNull(aisPacket.getTags()
                .getSourceId());
        
        if (sourceid.equals("")) {
            return;
        }
        
        Position p;
        try {
            p = Objects.requireNonNull(aisPacket.getAisMessage()
                    .getValidPosition());
            Integer cellid = p.getCellInt(1.0);

            if (timestamp > 0) {
                Integer time = AisMatSchema.getTimeBlock(timestamp,unit);
                try {
                    Long value = data.get(TypeSafeMapOfMaps.key(cellid,
                            sourceid, time));
                    data.put(TypeSafeMapOfMaps.key(cellid, sourceid, time),
                            value + 1);
                } catch (Exception e) {
                    data.put(TypeSafeMapOfMaps.key(cellid, sourceid, time), 0L);
                }

            }
        } catch (AisMessageException | SixbitException | NullPointerException e1) {
            // TODO Auto-generated catch block
            //e1.printStackTrace();
        }

    }

    @Override
    public List<RegularStatement> prepare() {
        LinkedList<RegularStatement> list = new LinkedList<>();
        for (Entry<Key3<Integer, String, Integer>, Long> e : data) {
            Insert insert = QueryBuilder
                    .insertInto(AisMatSchema.VIEW_KEYSPACE,AisMatSchema.TABLE_CELL1_SOURCE_TIME_COUNT)
                    .value(AisMatSchema.CELL1_KEY, e.getKey().getK1())
                    .value(AisMatSchema.SOURCE_KEY, e.getKey()
                    .getK2())
                    .value(AisMatSchema.TIME_KEY, e.getKey().getK3());
        
            list.add(insert);

        }
        return list;
    }
    
    @Override
    public HashViewBuilder level(TimeUnit unit) {
        this.unit = unit;
        return this;
    }
}