// CurrentSong.kt
package com.example.hothot

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.res.painterResource

val artistTextColor = Color(0x80FFFFFF)

@Composable
fun CurrentSongView(
    artistName: String?,
    songName: String?,
    albumArt: Bitmap?,
    isPlaying: Boolean,  // Receive the isPlaying state from MediaService
    onPlay: () -> Unit,  // These callbacks should call MediaService methods via MainActivity
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
    buttonColor: Color,
    textColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            //.padding(2.dp),
            .padding(top = 20.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(360.dp)
                .clip(RoundedCornerShape(26.dp))
        ) {
            if (albumArt != null) {
                Image(
                    bitmap = albumArt.asImageBitmap(),
                    contentDescription = "Album Art",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(360.dp)
                        .clip(RoundedCornerShape(26.dp))
                ) {
                    // Text("No Art", color = Color.White)
                    Image(
                        painter = painterResource(id = R.drawable.blank_album_art), // Replace with your image resource
                        contentDescription = "Default album art",
                      modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Tap zones overlay (works for both albumArt and no art box)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(26.dp))
            ) {
                // Left - Previous
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onPrevious() }
                )

                // Center - Play/Pause
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            if (isPlaying) {
                                onPause()
                            } else {
                                onPlay()
                            }
                        }
                ) {}

                // Right - Next
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onNext() }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 20.dp, start = 28.dp)
                .clickable { onBack() },
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = (songName?.take(30) ?: "No Song Playing") + if (songName?.length ?: 0 > 30) "..." else "",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = artistName ?: "No Artist",
                fontSize = 18.sp,
                color = artistTextColor,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }


        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
         //  Button(onClick = onPause, colors = ButtonDefaults.buttonColors(containerColor = buttonColor)) {
              //  Text("Pause", color = textColor, fontWeight = FontWeight.Bold)
           // }
            //Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = buttonColor)) {
              //  Text("Stop", color = textColor, fontWeight = FontWeight.Bold)
           // }
           // Button(onClick = onPlay, colors = ButtonDefaults.buttonColors(containerColor = buttonColor)) {
                //Text("Play", color = textColor, fontWeight = FontWeight.Bold)
            //}
        }

        Spacer(modifier = Modifier.height(16.dp))

       // Button(
          //  onClick = onBack,
           // colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
           // modifier = Modifier.fillMaxWidth()
       // ) {
        //    Text("Back to List", color = textColor, fontWeight = FontWeight.Bold)
        }
    }
//}