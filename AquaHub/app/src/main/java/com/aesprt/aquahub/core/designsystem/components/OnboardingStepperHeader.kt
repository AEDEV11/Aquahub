package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.ui.theme.AquaHubShapes
import com.aesprt.aquahub.ui.theme.AquaHubSpacing
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import com.aesprt.aquahub.ui.theme.GlassBorderAqua

private data class StepperStep(
    val stepNumber: Int,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

private val STEPPER_STEPS = listOf(
    StepperStep(1, "Account", "Owner Credentials", Icons.Rounded.Person),
    StepperStep(2, "Station", "Profile Details", Icons.Rounded.Storefront),
    StepperStep(3, "Plan", "Select Tier", Icons.Rounded.Diamond)
)

@Composable
fun OnboardingStepperHeader(
    currentStep: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeStep = STEPPER_STEPS.find { it.stepNumber == currentStep } ?: STEPPER_STEPS[0]

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
        border = BorderStroke(1.dp, GlassBorderAqua),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = AquaHubSpacing.medium, vertical = 10.dp)
        ) {
            // Top Row: Back Button & Step Badge
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Glass Circle Back Button
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, GlassBorderAqua),
                    modifier = Modifier.size(38.dp)
                ) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Sleek Step Indicator Pill Badge
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                    shape = AquaHubShapes.chip,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "STEP $currentStep OF 3 • ${activeStep.title.uppercase()}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.8.sp,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stepper Visual Nodes with Connecting Progress Lines
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                STEPPER_STEPS.forEachIndexed { index, step ->
                    val isCompleted = currentStep > step.stepNumber
                    val isActive = currentStep == step.stepNumber
                    val isPending = currentStep < step.stepNumber

                    val nodeBgColor by animateColorAsState(
                        targetValue = when {
                            isCompleted || isActive -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        animationSpec = tween(350),
                        label = "nodeBgColor"
                    )

                    val nodeContentColor by animateColorAsState(
                        targetValue = when {
                            isCompleted || isActive -> Color.White
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        animationSpec = tween(350),
                        label = "nodeContentColor"
                    )

                    // Step Node
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .then(
                                    if (isActive) {
                                        Modifier.shadow(
                                            elevation = 6.dp,
                                            shape = CircleShape,
                                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        )
                                    } else Modifier
                                )
                                .background(nodeBgColor, CircleShape)
                                .then(
                                    if (isActive) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                    } else if (isPending) {
                                        Modifier.border(1.dp, GlassBorderAqua, CircleShape)
                                    } else Modifier
                                )
                        ) {
                            if (isCompleted) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Completed",
                                    tint = nodeContentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = step.icon,
                                    contentDescription = step.title,
                                    tint = nodeContentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else if (isCompleted) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                },
                                fontSize = 12.sp
                            )
                        )
                    }

                    // Connecting Line between Nodes (except last)
                    if (index < STEPPER_STEPS.size - 1) {
                        val isLineFilled = currentStep > step.stepNumber
                        val lineColor by animateColorAsState(
                            targetValue = if (isLineFilled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            },
                            animationSpec = tween(350),
                            label = "lineColor"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .height(2.5.dp)
                                .clip(CircleShape)
                                .background(lineColor)
                        )
                    }
                }
            }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun OnboardingStepperHeaderPreview() {
    AquaHubTheme {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OnboardingStepperHeader(currentStep = 1, onBackClick = {})
            OnboardingStepperHeader(currentStep = 2, onBackClick = {})
            OnboardingStepperHeader(currentStep = 3, onBackClick = {})
        }
    }
}
