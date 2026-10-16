package com.hdmapreforged;

import java.util.function.*;
import lombok.*;

/** Icon groups toggled together, in order of precedence when icons overlap. */
@RequiredArgsConstructor
enum Layer
{
    DUNGEONS(-0.5, HdMapReforgedConfig::showDungeons),
    TRANSPORTS(Double.NEGATIVE_INFINITY, HdMapReforgedConfig::showTransports),
    TELEPORTS(-2.5, HdMapReforgedConfig::showTeleports),
    SAILING(-1.5, HdMapReforgedConfig::showSailing),
    SKILLING(1.5, HdMapReforgedConfig::showSkilling),
    ACTIVITIES(-1, HdMapReforgedConfig::showActivities),
    SERVICES(1, HdMapReforgedConfig::showServices),
    GAME_ICONS(Double.NEGATIVE_INFINITY, config -> true);

    /** Hidden below this zoom, so the zoomed-out map stays readable. */
    final double minZoom;
    final Predicate<HdMapReforgedConfig> setting;
}
