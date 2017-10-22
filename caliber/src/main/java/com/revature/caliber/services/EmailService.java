package com.revature.caliber.services;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.revature.caliber.email.Mailer;

/**
 * Starts the email job
 * @author Andrew Bonds
 * @author Will Underwood
 * @author Vladimir Yevseenko
 */
@Service
public class EmailService implements InitializingBean {
	
	private static final Logger logger = Logger.getLogger(EmailService.class);

	@Autowired
	private Mailer mailer;
	
	private static final ScheduledExecutorService scheduler =
		Executors.newScheduledThreadPool(1);
	
	private static final ZoneId TIME_ZONE = ZoneId.of("America/New_York");
<<<<<<< HEAD

	private static final DayOfWeek DAY_OF_WEEK_TO_FIRE = DayOfWeek.FRIDAY;
	private static final int HOUR_TO_FIRE = 9; // hours go 0-23
	private static final int MINUTE_TO_FIRE = 17; // minutes go 0-59
=======
	private static final DayOfWeek DAY_OF_WEEK_TO_FIRE = DayOfWeek.THURSDAY;
	private static final int HOUR_TO_FIRE = 22; // hours go 0-23
	private static final int MINUTE_TO_FIRE = 34; // minutes go 0-59
>>>>>>> d4a226816c550ed021d76a4a8ded51c24a49663b
	private static final int DAYS_BETWEEN_EMAILS = 7;
	private static final int INITIAL_DELAY = 0;
	private static final long SECONDS_BETWEEN_EMAILS = TimeUnit.DAYS.toSeconds(DAYS_BETWEEN_EMAILS);
	
	public void setMailer(Mailer mailer) {
		this.mailer = mailer;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		startReminderJob();
	}

	private void startReminderJob() {
		logger.info("startReminderJob()");
		
		LocalTime localTime = LocalTime.of(HOUR_TO_FIRE, MINUTE_TO_FIRE);
		LocalDate localDate = LocalDate.now().with(TemporalAdjusters.next(DAY_OF_WEEK_TO_FIRE));
		ZonedDateTime timeToFire = ZonedDateTime.of(localDate, localTime, TIME_ZONE);
	
		logger.info(timeToFire);
		
		scheduler.scheduleAtFixedRate(mailer, INITIAL_DELAY, DAYS_BETWEEN_EMAILS, TimeUnit.DAYS);

	}

}
