package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.domain.model.ChartDataPoint
import com.aesprt.aquahub.ui.theme.*
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@AquaHubPhoneAndTabletPreview
@Composable
private fun AquaHubChartsPreview() {
    val weekly = listOf(
        ChartDataPoint("Mon", 3200.0),
        ChartDataPoint("Tue", 4100.0),
        ChartDataPoint("Wed", 2800.0),
        ChartDataPoint("Thu", 5600.0),
        ChartDataPoint("Fri", 4900.0),
        ChartDataPoint("Sat", 7200.0),
        ChartDataPoint("Sun", 6400.0)
    )
    AquaHubTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(AquaHubSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
        ) {
            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Revenue trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                AnimatedRevenueLineChart(dataPoints = weekly)
            }
            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Orders by day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                AnimatedOrdersBarChart(dataPoints = weekly)
            }
            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Order status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                AnimatedDonutChart(
                    dataPoints = listOf(
                        ChartDataPoint("Delivered", 42.0),
                        ChartDataPoint("Pending", 9.0),
                        ChartDataPoint("Cancelled", 3.0)
                    )
                )
            }
            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Top products", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                AnimatedHorizontalRankBar(
                    rank = 1,
                    title = "5 Gallon Refill",
                    subtitle = "124 orders",
                    value = 124.0,
                    maxValue = 160.0,
                    formattedValue = "124"
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// 1. Animated Revenue Line & Area Chart
// -------------------------------------------------------------------------
@Composable
fun AnimatedRevenueLineChart(
    dataPoints: List<ChartDataPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = AquaCyan,
    fillColor: Color = AquaCyan.copy(alpha = 0.25f),
    currencyFormat: NumberFormat = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) }
) {
    if (dataPoints.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No revenue activity in this period",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
        return
    }

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(dataPoints) {
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
        )
    }

    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    val maxVal = (dataPoints.maxOfOrNull { it.value } ?: 100.0).coerceAtLeast(10.0)

    Column(modifier = modifier.fillMaxWidth()) {
        if (selectedPointIndex != null && selectedPointIndex in dataPoints.indices) {
            val pt = dataPoints[selectedPointIndex!!]
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Surface(
                    shape = AquaHubShapes.chip,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, lineColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "${pt.label}: ${currencyFormat.format(pt.value)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(dataPoints) {
                    detectTapGestures { offset ->
                        val count = dataPoints.size
                        if (count > 1) {
                            val spacing = size.width / (count - 1)
                            val index = ((offset.x + spacing / 2) / spacing).toInt().coerceIn(0, count - 1)
                            selectedPointIndex = index
                        } else {
                            selectedPointIndex = 0
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height - 30.dp.toPx()
            val pointCount = dataPoints.size

            if (pointCount < 2) {
                // Single point horizontal line
                val y = height / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(width * animProgress.value, y),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                return@Canvas
            }

            val stepX = width / (pointCount - 1)
            val path = Path()
            val fillPath = Path()

            val points = dataPoints.mapIndexed { idx, dp ->
                val x = idx * stepX
                val normalizedY = (dp.value / maxVal).toFloat()
                val y = height - (normalizedY * height * animProgress.value)
                Offset(x, y)
            }

            path.moveTo(points.first().x, points.first().y)
            fillPath.moveTo(points.first().x, height)
            fillPath.lineTo(points.first().x, points.first().y)

            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val controlX1 = p0.x + (p1.x - p0.x) / 2f
                val controlY1 = p0.y
                val controlX2 = p0.x + (p1.x - p0.x) / 2f
                val controlY2 = p1.y

                path.cubicTo(controlX1, controlY1, controlX2, controlY2, p1.x, p1.y)
                fillPath.cubicTo(controlX1, controlY1, controlX2, controlY2, p1.x, p1.y)
            }

            fillPath.lineTo(points.last().x, height)
            fillPath.close()

            // Area Gradient Fill
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(fillColor, fillColor.copy(alpha = 0.0f)),
                    startY = 0f,
                    endY = height
                )
            )

            // Stroke Line
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            // Point Dots
            points.forEachIndexed { index, pt ->
                val isSelected = selectedPointIndex == index
                drawCircle(
                    color = if (isSelected) Color.White else lineColor,
                    radius = if (isSelected) 6.dp.toPx() else 4.dp.toPx(),
                    center = pt
                )
                if (isSelected) {
                    drawCircle(
                        color = lineColor,
                        radius = 8.dp.toPx(),
                        center = pt,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // X-Axis Labels
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            val step = (dataPoints.size / 6).coerceAtLeast(1)
            dataPoints.forEachIndexed { index, dp ->
                if (index == 0 || index == dataPoints.size - 1 || index % step == 0) {
                    Text(
                        text = dp.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// 2. Animated Orders Bar Chart
// -------------------------------------------------------------------------
@Composable
fun AnimatedOrdersBarChart(
    dataPoints: List<ChartDataPoint>,
    modifier: Modifier = Modifier,
    barColor: Color = AquaCyan,
    highlightColor: Color = SuccessGreen
) {
    if (dataPoints.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No order volume recorded",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
        return
    }

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(dataPoints) {
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
        )
    }

    val maxVal = (dataPoints.maxOfOrNull { it.value } ?: 10.0).coerceAtLeast(5.0)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val width = size.width
            val height = size.height - 20.dp.toPx()
            val barCount = dataPoints.size
            val barSlotWidth = width / barCount
            val barWidth = (barSlotWidth * 0.55f).coerceIn(8.dp.toPx(), 28.dp.toPx())

            dataPoints.forEachIndexed { idx, dp ->
                val centerX = (idx * barSlotWidth) + (barSlotWidth / 2f)
                val barHeight = ((dp.value / maxVal) * height * animProgress.value).toFloat()
                val topY = height - barHeight
                val isMax = dp.value == maxVal && dp.value > 0

                drawRoundRect(
                    color = if (isMax) highlightColor else barColor,
                    topLeft = Offset(centerX - (barWidth / 2f), topY),
                    size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }

        // Labels Row
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            val step = (dataPoints.size / 6).coerceAtLeast(1)
            dataPoints.forEachIndexed { index, dp ->
                if (index == 0 || index == dataPoints.size - 1 || index % step == 0) {
                    Text(
                        text = dp.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// 3. Animated Donut Status Chart
// -------------------------------------------------------------------------
@Composable
fun AnimatedDonutChart(
    dataPoints: List<ChartDataPoint>,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(SuccessGreen, WarningAmber, MaterialTheme.colorScheme.error)
) {
    val total = dataPoints.sumOf { it.value }
    if (total <= 0) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No status distribution data",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
        return
    }

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(dataPoints) {
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = modifier.fillMaxWidth()
    ) {
        // Donut Canvas with Center Total
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(130.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 16.dp.toPx()
                var currentAngle = -90f

                dataPoints.forEachIndexed { idx, dp ->
                    val sweepAngle = ((dp.value / total) * 360f * animProgress.value).toFloat()
                    val col = colors.getOrElse(idx) { AquaCyan }

                    drawArc(
                        color = col,
                        startAngle = currentAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                    currentAngle += sweepAngle
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${total.toInt()}",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }

        // Legend Column
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            dataPoints.forEachIndexed { idx, dp ->
                val col = colors.getOrElse(idx) { AquaCyan }
                val pct = if (total > 0) ((dp.value / total) * 100).toInt() else 0

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(col, CircleShape)
                    )
                    Text(
                        text = "${dp.label}:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = "${dp.value.toInt()} ($pct%)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = col)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// 4. Animated Top Products / Riders Horizontal Bar Rank
// -------------------------------------------------------------------------
@Composable
fun AnimatedHorizontalRankBar(
    rank: Int,
    title: String,
    subtitle: String,
    value: Double,
    maxValue: Double,
    formattedValue: String,
    modifier: Modifier = Modifier,
    barColor: Color = AquaCyan
) {
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(value, maxValue) {
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = if (maxValue > 0) (value / maxValue).toFloat().coerceIn(0f, 1f) else 0f,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (rank == 1) WarningAmberLight else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "#$rank",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (rank == 1) WarningAmber else MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }

            Text(
                text = formattedValue,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = animProgress.value)
                    .fillMaxHeight()
                    .background(barColor, CircleShape)
            )
        }
    }
}
