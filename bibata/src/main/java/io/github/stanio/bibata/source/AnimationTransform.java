/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class AnimationTransform {

    private final float fullDuration;

    private float snapshotTime;

    AnimationTransform(float duration) {
        this.fullDuration = duration;
    }

    public static AnimationTransform cast(Object obj) {
        if (obj == null || obj instanceof String && obj.toString().isEmpty()) {
            throw new IllegalStateException("colorWheel parameter is not set");
        }
        return (AnimationTransform) obj;
    }

    void snapshotTime(float time) {
        this.snapshotTime = time;
    }

    /**
     * Computes the rotation angle for the {@linkplain #snapshotTime(float)
     * current animation time}.
     *
     * @param   transformAttributes  {@code svg:animateTransform/@*}
     * @return  the angle for a static
     *          <code>transform="rotate(<var>angle</var>)"</code>
     */
    public String rotate(NodeList transformAttributes) {
        Map<String, String> attributes = attributeMap(transformAttributes);
        final int to = 360; // assume from="0" to="360"
        float repeatCount = Float.parseFloat(attributes.get("repeatCount"));
        float angle = (repeatCount * to) * snapshotTime / fullDuration;
        return "rotate(" + BigDecimal.valueOf(angle)
                                     .setScale(2, RoundingMode.HALF_EVEN)
                                     .stripTrailingZeros()
                                     .toPlainString() + ")";
    }

    private static Map<String, String> attributeMap(NodeList attributeList) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0, len = attributeList.getLength(); i < len; i++) {
            Node item = attributeList.item(i);
            map.put(item.getNodeName(), item.getNodeValue());
        }
        return map;
    }

}
