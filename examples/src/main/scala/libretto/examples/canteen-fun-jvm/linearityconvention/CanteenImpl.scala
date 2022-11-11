package canteen.linearityconvention

class CanteenImpl extends Canteen {
  import CanteenImpl.SessionImpl

  override def enter(): Canteen.Session =
    new SessionImpl
}

object CanteenImpl {
  import Canteen._

  private class SessionImpl extends Session {

    override def proceedToSoups(): SectionSoup =
      new SectionSoupImpl

  }

  private class SectionSoupImpl extends SectionSoup {
    override def getSoup(): Either[(Soup, SectionSoup), SectionMain] =
      cookSoup() match {
        case Some(soup) => Left((soup, new SectionSoupImpl))
        case None       => Right(new SectionMainImpl)
      }

    override def proceedToMainDishes(): SectionMain =
      new SectionMainImpl
  }

  private class SectionMainImpl extends SectionMain {
    override def getMainDish(): Either[(MainDish, SectionMain), SectionDessert] =
      cookMainDish() match {
        case Some(dish) => Left((dish, new SectionMainImpl))
        case None       => Right(new SectionDessertImpl)
      }

    override def proceedToDesserts(): SectionDessert =
      new SectionDessertImpl
  }

  private class SectionDessertImpl extends SectionDessert {
    override def getDessert(): Either[(Dessert, SectionDessert), SectionPayment] =
      cookDessert() match {
        case Some(dessert) => Left((dessert, new SectionDessertImpl))
        case None          => Right(new SectionPaymentImpl)
      }

    override def proceedToPayment(): SectionPaymentImpl =
      new SectionPaymentImpl
  }

  private class SectionPaymentImpl extends SectionPayment {
    override def payAndClose(card: PaymentCard): Unit =
      ()
  }


  private def cookSoup(): Option[Soup] =
    None // out of soup

  private def cookMainDish(): Option[MainDish] =
    Some(new MainDish())

  private def cookDessert(): Option[Dessert] =
    Some(new Dessert())

  private def processPayment(card: PaymentCard): Unit =
    ()
}
