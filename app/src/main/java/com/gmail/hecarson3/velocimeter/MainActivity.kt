package com.gmail.hecarson3.velocimeter

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment

class MainActivity : AppCompatActivity(), VelocimeterFragment.SettingsNavigator {

    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
//        navController = findNavController(R.id.fragmentContainerView)
        navController = navHostFragment.navController
    }

    override fun onSettingsNavigate() {
        navController.navigate(R.id.action_velocimeterFragment_to_settingsFragment)
    }

}